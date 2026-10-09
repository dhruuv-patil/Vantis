"""Workflow generation pipeline: structured extraction, validation, repair loop, and normalization."""

from __future__ import annotations

import json
import logging
import re
import time
import uuid
from typing import Any

from app.node_catalog import catalog_prompt
from app.providers.base import LLMProvider, ProviderError
from app.validator import normalize_workflow, validate_workflow_graph

logger = logging.getLogger("flowops.ai")

MAX_GENERATION_REPAIR_ATTEMPTS = 2


class WorkflowGenerationError(Exception):
    """Raised when AI workflow generation fails validation or cannot produce a valid graph."""

    def __init__(
        self,
        message: str,
        code: str = "AI_GENERATION_FAILED",
        details: list[dict[str, Any]] | None = None,
        cause: Exception | None = None,
    ):
        super().__init__(message)
        self.message = message
        self.code = code
        self.details = details or []
        self.cause = cause


def extract_json_payload(raw_text: str) -> dict[str, Any]:
    """Robustly extract and parse a JSON object from model output without unsafe evaluation.

    - Strips markdown code block fences (```json ... ```)
    - Finds the outermost { ... } payload if enclosed in explanation
    - Strictly rejects malformed or non-object JSON
    """
    if not raw_text or not raw_text.strip():
        raise WorkflowGenerationError("The AI provider returned an empty response.", code="EMPTY_RESPONSE")

    cleaned = raw_text.strip()

    # Remove markdown code block markers
    if cleaned.startswith("```"):
        lines = cleaned.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        cleaned = "\n".join(lines).strip()

    # If already starts with { and ends with }, parse directly
    if cleaned.startswith("{") and cleaned.endswith("}"):
        try:
            parsed = json.loads(cleaned)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    # Extract outermost balanced braces
    start_idx = cleaned.find("{")
    end_idx = cleaned.rfind("}")
    if start_idx != -1 and end_idx != -1 and end_idx > start_idx:
        candidate = cleaned[start_idx : end_idx + 1]
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError as exc:
            raise WorkflowGenerationError(
                "The AI provider returned malformed JSON that could not be parsed.",
                code="MALFORMED_JSON",
                cause=exc,
            ) from exc

    raise WorkflowGenerationError(
        "No valid JSON object could be extracted from the AI provider response.",
        code="MALFORMED_JSON",
    )


def _generation_system_prompt() -> str:
    return (
        "You are a workflow architect for Vantis, an enterprise reliability and automation platform.\n"
        "Turn the user's prompt into an executable directed graph of nodes.\n\n"
        "CRITICAL RULES:\n"
        "1. Available node types (use ONLY these registered types, NEVER invent node types):\n"
        f"{catalog_prompt()}\n\n"
        "2. The workflow MUST start with exactly one TRIGGER node (prefer manual_trigger unless webhook is specified).\n"
        "3. Every node must have a unique alphanumeric id (e.g. \"trigger\", \"http_1\").\n"
        "4. Connect nodes with edges: {\"source\": \"...\", \"target\": \"...\", \"sourceHandle\": null}.\n"
        "   For branching nodes (condition, human_approval, switch), sourceHandle MUST be explicitly set to the output name.\n"
        "5. Every branch MUST terminate at a valid action or outcome node (never drop branches).\n"
        "6. Do NOT include hardcoded secrets (API keys, passwords, tokens). Use integrationId references (e.g. \"default\" or connection names) or {{secrets.KEY}}.\n"
        "7. External URLs must be public http/https (never localhost, 127.0.0.1, internal IPs, or cloud metadata).\n"
        "8. Data references like {{node_id.output.field}} or {{node_id.status}} must only reference prior upstream nodes.\n"
        "9. Only set declared configuration keys for that node type; do not invent config properties.\n\n"
        "Respond with a single JSON object of this exact shape and nothing else:\n"
        '{"nodes": [{"id": "...", "type": "...", "label": "...", "config": {}}], '
        '"edges": [{"source": "...", "target": "...", "sourceHandle": null}], '
        '"notes": "one-sentence summary"}'
    )


def _repair_prompt(user_prompt: str, previous_json: dict[str, Any], validation_errors: list[dict[str, Any]]) -> str:
    errors_str = json.dumps(validation_errors, indent=2)
    prev_str = json.dumps(previous_json, indent=2)
    return (
        f"Original User Request:\n{user_prompt}\n\n"
        f"Your previous workflow generation failed validation with the following machine-readable errors:\n"
        f"{errors_str}\n\n"
        f"Previous Workflow JSON:\n{prev_str}\n\n"
        "INSTRUCTIONS:\n"
        "Repair the workflow by addressing each listed validation error.\n"
        "- Do not use invented node types or unsupported config keys.\n"
        "- Ensure all nodes connect from the trigger without loops or unreachable nodes.\n"
        "- Ensure all condition and approval branches are connected to destination nodes.\n"
        "- Ensure all {{ ... }} references only refer to existing upstream nodes.\n"
        "- Do not hardcode credentials or private URLs.\n"
        "Return ONLY the fixed JSON object matching the Vantis workflow schema."
    )


class WorkflowGenerator:
    """Manages prompt execution, JSON parsing, strict validation, bounded repair, and canonical normalization."""

    def __init__(self, provider: LLMProvider) -> None:
        self.provider = provider

    def generate(self, user_prompt: str, correlation_id: str | None = None) -> dict[str, Any]:
        cid = correlation_id or str(uuid.uuid4())
        start_time = time.time()
        logger.info("[%s] Starting workflow generation for prompt length=%d", cid, len(user_prompt))

        # Check configuration
        if not self.provider.is_configured():
            raise WorkflowGenerationError("AI provider is not configured.", code="AI_NOT_CONFIGURED")

        system_prompt = _generation_system_prompt()
        provider_name = getattr(self.provider, "name", "unknown")
        model_name = self.provider.default_model() if hasattr(self.provider, "default_model") else "unknown"

        # Initial generation attempt
        gen_attempt = 1
        repair_attempt = 0
        content = ""
        try:
            call_res = self.provider.generate_json(system_prompt, user_prompt)
            if isinstance(call_res, tuple):
                content, used_provider = call_res
                provider_name = used_provider
            else:
                content = call_res
            logger.info(
                "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d response_status=ok response_length=%d",
                cid, provider_name, model_name, gen_attempt, repair_attempt, len(content) if content else 0,
            )
        except ProviderError as exc:
            logger.warning(
                "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d response_status=error error=%s",
                cid, provider_name, model_name, gen_attempt, repair_attempt, str(exc),
            )
            raise WorkflowGenerationError(str(exc), code="PROVIDER_ERROR", cause=exc) from exc

        try:
            parsed_data = extract_json_payload(content)
            logger.info(
                "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d extraction_result=success",
                cid, provider_name, model_name, gen_attempt, repair_attempt,
            )
        except WorkflowGenerationError as exc:
            logger.warning(
                "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d extraction_result=failed error=%s",
                cid, provider_name, model_name, gen_attempt, repair_attempt, str(exc),
            )
            raise

        report = validate_workflow_graph(parsed_data)
        if not report.valid:
            for err in report.errors:
                logger.warning(
                    "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d validator_name=%s validator_error_code=%s validator_message=%s node_id=%s edge_id=%s",
                    cid, provider_name, model_name, gen_attempt, repair_attempt,
                    getattr(err, "validator", "validator") or "validator",
                    err.code, err.message, err.node_id or "", err.edge_id or "",
                )

        # Repair loop if initial generation has validation errors
        while not report.valid and repair_attempt < MAX_GENERATION_REPAIR_ATTEMPTS:
            repair_attempt += 1
            gen_attempt += 1
            error_feedback = [e.to_repair_error() for e in report.errors]
            logger.warning(
                "[%s] provider=%s model=%s repair_attempt=%d/%d with %d validation errors. Initiating AI repair.",
                cid, provider_name, model_name, repair_attempt, MAX_GENERATION_REPAIR_ATTEMPTS, len(error_feedback),
            )

            repair_user_prompt = _repair_prompt(user_prompt, parsed_data, error_feedback)
            try:
                repair_res = self.provider.generate_json(system_prompt, repair_user_prompt)
                if isinstance(repair_res, tuple):
                    repair_content, used_p = repair_res
                    provider_name = used_p
                else:
                    repair_content = repair_res
                logger.info(
                    "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d response_status=ok response_length=%d",
                    cid, provider_name, model_name, gen_attempt, repair_attempt, len(repair_content) if repair_content else 0,
                )
                parsed_data = extract_json_payload(repair_content)
                logger.info(
                    "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d extraction_result=success",
                    cid, provider_name, model_name, gen_attempt, repair_attempt,
                )
                report = validate_workflow_graph(parsed_data)
                if not report.valid:
                    for err in report.errors:
                        logger.warning(
                            "[%s] provider=%s model=%s generation_attempt=%d repair_attempt=%d validator_name=%s validator_error_code=%s validator_message=%s node_id=%s edge_id=%s",
                            cid, provider_name, model_name, gen_attempt, repair_attempt,
                            getattr(err, "validator", "validator") or "validator",
                            err.code, err.message, err.node_id or "", err.edge_id or "",
                        )
            except (ProviderError, WorkflowGenerationError) as exc:
                logger.warning(
                    "[%s] provider=%s model=%s repair_attempt=%d failed: %s",
                    cid, provider_name, model_name, repair_attempt, str(exc),
                )
                break

        elapsed = time.time() - start_time

        if not report.valid:
            logger.error(
                "[%s] provider=%s model=%s Workflow generation failed after %d repair attempts. %d remaining errors. Latency: %.2fs",
                cid,
                provider_name,
                model_name,
                repair_attempt,
                len(report.errors),
                elapsed,
            )
            for err in report.errors:
                logger.warning(
                    "[%s] Unresolved validation error: validator_name=%s validator_error_code=%s validator_message=%s node_id=%s edge_id=%s path=%s",
                    cid,
                    getattr(err, "validator", "validator") or "validator",
                    err.code,
                    err.message,
                    err.node_id or "",
                    err.edge_id or "",
                    err.path or "",
                )

            raise WorkflowGenerationError(
                "The AI could not turn that into a workflow. Try rephrasing your prompt.",
                code="AI_GENERATION_FAILED",
                details=[e.to_repair_error() for e in report.errors],
            )

        logger.info(
            "[%s] provider=%s model=%s Workflow generated and validated successfully (repairs=%d, nodes=%d, edges=%d, latency=%.2fs)",
            cid,
            provider_name,
            model_name,
            repair_attempt,
            len(parsed_data.get("nodes", [])),
            len(parsed_data.get("edges", [])),
            elapsed,
        )

        # Deterministic canonical normalization
        normalized = normalize_workflow(parsed_data)
        return normalized
