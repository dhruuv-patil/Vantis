"""OmniRoute provider for FlowOps.

Uses OmniRoute's OpenAI-compatible API. Supports:
- Structured JSON workflow generation
- Tool-calling agent execution

No fake/fallback output is returned. Provider failures raise ProviderError.
"""

from __future__ import annotations

import json
import logging
import re

from openai import (
    APIConnectionError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    InternalServerError,
    NotFoundError,
    OpenAI,
    OpenAIError,
    RateLimitError,
)

from app.config import get_settings
from app.providers.base import AgentResult, ProviderError, ToolInvocation
from app.tools import execute_tool, function_specs

logger = logging.getLogger("flowops.ai")


def _safe_error_detail(exc: Exception) -> str:
    """Return a safe error representation with any secrets redacted."""
    msg = str(exc)
    msg = re.sub(r"sk-[a-zA-Z0-9_\-]+", "sk-***", msg)
    msg = re.sub(r"(bearer\s+)[a-zA-Z0-9_\.\-]+", r"\1***", msg, flags=re.IGNORECASE)
    parts = [f"type={type(exc).__name__}"]
    status_code = getattr(exc, "status_code", None)
    if status_code is not None:
        parts.append(f"code={status_code}")
    parts.append(f"detail={msg[:200]}")
    return " ".join(parts)


def _clean_json_text(text: str) -> str:
    """Strip markdown code block fences if returned by the model."""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        lines = cleaned.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        cleaned = "\n".join(lines).strip()
    return cleaned


class OmniRouteProvider:
    """LLM provider backed by a local OmniRoute OpenAI-compatible gateway."""

    name = "omniroute"

    def __init__(self) -> None:
        self.settings = get_settings()

    def is_configured(self) -> bool:
        return bool(
            self.settings.omniroute_base_url.strip()
            and self.settings.omniroute_api_key.strip()
        )

    def default_model(self) -> str:
        return self.settings.omniroute_model

    def _client(self) -> OpenAI:
        if not self.is_configured():
            raise ProviderError("OmniRoute provider is not configured.")

        try:
            return OpenAI(
                base_url=self.settings.omniroute_base_url,
                api_key=self.settings.omniroute_api_key,
                timeout=self.settings.provider_timeout_seconds,
                max_retries=1,
            )
        except Exception as exc:
            logger.warning("omniroute client init failed: %s", _safe_error_detail(exc))
            raise ProviderError(
                f"Failed to initialize OmniRoute client: {type(exc).__name__}"
            ) from exc

    def _handle_provider_error(self, exc: Exception, used_model: str) -> ProviderError:
        """Map provider exceptions to appropriate ProviderError and log safely."""
        if isinstance(exc, ProviderError):
            return exc

        logger.warning("omniroute request failed: %s", _safe_error_detail(exc))

        if isinstance(exc, AuthenticationError):
            return ProviderError("OmniRoute authentication failed: invalid or missing API key.")
        if isinstance(exc, APITimeoutError):
            return ProviderError("The OmniRoute AI provider timed out.")
        if isinstance(exc, APIConnectionError):
            return ProviderError("The OmniRoute AI provider could not be reached.")
        if isinstance(exc, NotFoundError):
            return ProviderError(f"OmniRoute model not found: {used_model}")
        if isinstance(exc, RateLimitError):
            return ProviderError("OmniRoute rate limit exceeded.")
        if isinstance(exc, BadRequestError):
            return ProviderError("OmniRoute request was invalid.")
        if isinstance(exc, InternalServerError):
            return ProviderError("OmniRoute provider encountered an internal server error.")
        if isinstance(exc, OpenAIError):
            return ProviderError(f"OmniRoute provider error: {type(exc).__name__}")

        return ProviderError(f"OmniRoute workflow generation failed: {type(exc).__name__}")

    def generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
    ) -> str:
        """Generate workflow JSON using the OpenAI-compatible chat API."""

        client = self._client()
        used_model = model or self.default_model()

        try:
            response = client.chat.completions.create(
                model=used_model,
                messages=[
                    {
                        "role": "system",
                        "content": system_prompt,
                    },
                    {
                        "role": "user",
                        "content": user_prompt,
                    },
                ],
                response_format={"type": "json_object"},
            )

            if not response.choices:
                raise ProviderError("OmniRoute returned no completion choices.")

            content = response.choices[0].message.content

            if not content or not content.strip():
                raise ProviderError("OmniRoute returned an empty response.")

            content = _clean_json_text(content)

            # Validate that the provider actually returned JSON.
            try:
                json.loads(content)
            except json.JSONDecodeError as exc:
                logger.warning("omniroute returned invalid JSON")
                raise ProviderError(
                    "OmniRoute returned invalid JSON."
                ) from exc

            return content

        except Exception as exc:
            raise self._handle_provider_error(exc, used_model) from exc

    def run_agent(
        self,
        instructions: str,
        model: str | None,
        user_input: str,
        enabled_tools: list[str],
    ) -> AgentResult:
        """Run a bounded OpenAI-compatible tool-calling loop."""

        client = self._client()
        used_model = model or self.default_model()

        tools = self._build_tools(enabled_tools)

        messages: list[dict] = [
            {
                "role": "system",
                "content": instructions,
            },
            {
                "role": "user",
                "content": user_input,
            },
        ]

        tool_calls: list[ToolInvocation] = []

        try:
            for _ in range(self.settings.agent_max_iterations):
                kwargs = {
                    "model": used_model,
                    "messages": messages,
                }

                if tools:
                    kwargs["tools"] = tools
                    kwargs["tool_choice"] = "auto"

                response = client.chat.completions.create(**kwargs)

                if not response.choices:
                    raise ProviderError(
                        "OmniRoute returned no completion choices."
                    )

                message = response.choices[0].message

                # Normal final answer.
                if not message.tool_calls:
                    output = message.content or ""

                    if not output.strip():
                        raise ProviderError(
                            "OmniRoute returned an empty agent response."
                        )

                    return AgentResult(
                        output=output,
                        model=used_model,
                        tool_calls=tool_calls,
                    )

                # Preserve assistant tool-call message.
                assistant_message = {
                    "role": "assistant",
                    "content": message.content,
                    "tool_calls": [],
                }

                for call in message.tool_calls:
                    assistant_message["tool_calls"].append(
                        {
                            "id": call.id,
                            "type": "function",
                            "function": {
                                "name": call.function.name,
                                "arguments": call.function.arguments,
                            },
                        }
                    )

                messages.append(assistant_message)

                # Execute every requested tool through FlowOps' existing
                # allowlist -> validate -> execute -> sanitize pipeline.
                for call in message.tool_calls:
                    name = call.function.name

                    try:
                        arguments = json.loads(call.function.arguments or "{}")
                    except json.JSONDecodeError as exc:
                        raise ProviderError(
                            f"Model returned invalid arguments for tool {name!r}."
                        ) from exc

                    if not isinstance(arguments, dict):
                        raise ProviderError(
                            f"Tool arguments for {name!r} must be an object."
                        )

                    try:
                        result = execute_tool(name, arguments)
                    except Exception as exc:
                        raise ProviderError(
                            f"Tool execution failed for {name!r}: "
                            f"{type(exc).__name__}"
                        ) from exc

                    result_text = (
                        result
                        if isinstance(result, str)
                        else json.dumps(result)
                    )

                    tool_calls.append(
                        ToolInvocation(
                            name=name,
                            arguments=arguments,
                            result=result_text,
                        )
                    )

                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call.id,
                            "content": result_text,
                        }
                    )

            raise ProviderError(
                "Agent exceeded the maximum number of tool-calling iterations."
            )

        except Exception as exc:
            raise self._handle_provider_error(exc, used_model) from exc

    def _build_tools(self, enabled_tools: list[str]) -> list[dict]:
        """Convert FlowOps tool specs into OpenAI-compatible schemas."""

        if not enabled_tools:
            return []

        allowed = set(enabled_tools)
        result: list[dict] = []

        for spec in function_specs:
            # Support either dict-style or object-style existing specs.
            if isinstance(spec, dict):
                name = spec.get("name")
                description = spec.get("description", "")
                parameters = spec.get("parameters", {})
            else:
                name = getattr(spec, "name", None)
                description = getattr(spec, "description", "")
                parameters = getattr(spec, "parameters", {})

            if not name or name not in allowed:
                continue

            result.append(
                {
                    "type": "function",
                    "function": {
                        "name": name,
                        "description": description,
                        "parameters": parameters,
                    },
                }
            )

        return result