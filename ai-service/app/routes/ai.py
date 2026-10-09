"""The `/ai/*` endpoints: workflow generation, agent execution, and anomaly investigation.

All endpoints are guarded by ``require_service_token`` (only the Spring backend calls
this service) and short-circuit to ``configured: false`` when no provider
key is set — they never fabricate output. The concrete LLM is chosen via
:func:`app.providers.get_provider` (Gemini by default), so these handlers are
provider-agnostic. Provider/transport failures surface as 502s with a safe
message; the API key is never included in any error or log.
"""

import json
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, status

from app.node_catalog import catalog_prompt
from app.providers import ProviderError, get_provider
from app.schemas import (
    AgentRunRequest,
    AgentRunResponse,
    CompleteRequest,
    CompleteResponse,
    GeneratedEdge,
    GeneratedGraph,
    GeneratedNode,
    GenerateWorkflowRequest,
    GenerateWorkflowResponse,
    ToolCallRecord,
    InvestigateAnomalyRequest,
    InvestigateAnomalyResponse,
    LikelyCause,
    EvidenceItem,
    RecommendedAction,
)
from app.security import require_service_token
from app.tools import available_tool_names

logger = logging.getLogger("flowops.ai")

router = APIRouter(prefix="/ai", tags=["ai"], dependencies=[Depends(require_service_token)])


def is_configured() -> bool:
    """Whether the active provider has credentials.

    A module-level indirection over the provider so the honesty short-circuit
    stays cheap (no SDK import, no client build) and remains trivially
    substitutable in tests.
    """
    return get_provider().is_configured()


# --------------------------------------------------------------- generation

def _generation_system_prompt() -> str:
    return (
        "You are a workflow architect for Vantis, a reliability infrastructure for automation platform. "
        "Turn the user's request into a directed graph of nodes.\n\n"
        "Available node types (use ONLY these):\n"
        f"{catalog_prompt()}\n\n"
        "Rules:\n"
        "- The graph MUST begin with exactly one TRIGGER node (prefer manual_trigger "
        "unless the request clearly describes an incoming HTTP call).\n"
        "- Give every node a short, unique string id (e.g. \"trigger\", \"http_1\").\n"
        "- Only set config keys listed for that node type; omit keys you don't need.\n"
        "- Connect nodes with edges {source, target}. For condition/human_approval "
        "nodes set sourceHandle to the correct output name.\n"
        "- For ai_agent nodes, put the system prompt in config.instructions.\n"
        "- Keep it minimal: only the nodes needed to fulfil the request.\n\n"
        "Respond with a single JSON object of this exact shape and nothing else:\n"
        '{"nodes": [{"id": "...", "type": "...", "label": "...", '
        '"config": {}}], "edges": [{"source": "...", "target": "...", '
        '"sourceHandle": null}], "notes": "one-sentence summary"}'
    )


def _coerce_graph(data: dict) -> GeneratedGraph:
    """Best-effort parse of the model's JSON into our graph shape.

    Malformed individual nodes/edges are skipped rather than failing the whole
    request; the backend re-validates against its NodeRegistry regardless.
    """
    nodes: list[GeneratedNode] = []
    for raw in data.get("nodes", []) or []:
        if not isinstance(raw, dict):
            continue
        try:
            nodes.append(GeneratedNode.model_validate(raw))
        except Exception:  # noqa: BLE001 — skip a bad node, keep the rest
            continue

    edges: list[GeneratedEdge] = []
    for raw in data.get("edges", []) or []:
        if not isinstance(raw, dict):
            continue
        # Accept either camelCase (prompted) or snake_case.
        if "sourceHandle" in raw and "source_handle" not in raw:
            raw = {**raw, "source_handle": raw.get("sourceHandle")}
        try:
            edges.append(GeneratedEdge.model_validate(raw))
        except Exception:  # noqa: BLE001
            continue

    return GeneratedGraph(nodes=nodes, edges=edges)


@router.post("/generate-workflow", response_model=GenerateWorkflowResponse)
def generate_workflow(
    req: GenerateWorkflowRequest,
    x_request_id: str | None = Header(None, alias="X-Request-Id"),
) -> GenerateWorkflowResponse:
    if not is_configured():
        return GenerateWorkflowResponse(configured=False)

    from app.providers.gemini import GeminiProvider
    from app.providers.omniroute import OmniRouteProvider
    from app.providers.router import ProviderRouter
    from app.workflow_generator import WorkflowGenerator, WorkflowGenerationError

    primary_provider = get_provider()
    fallback_provider = None
    try:
        pname = getattr(primary_provider, "name", "").lower()
        if pname == "omniroute":
            g = GeminiProvider()
            if g.is_configured():
                fallback_provider = g
        elif pname == "gemini":
            o = OmniRouteProvider()
            if o.is_configured():
                fallback_provider = o
    except Exception:
        fallback_provider = None

    router = ProviderRouter(primary=primary_provider, fallback=fallback_provider)
    generator = WorkflowGenerator(router)
    try:
        normalized_data = generator.generate(req.prompt, correlation_id=x_request_id)
    except WorkflowGenerationError as exc:
        if exc.code == "AI_NOT_CONFIGURED":
            return GenerateWorkflowResponse(configured=False)
        if exc.code == "PROVIDER_ERROR":
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=exc.message)
        # Validation failure after repair
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=exc.message,
        )

    nodes = [GeneratedNode.model_validate(n) for n in normalized_data.get("nodes", [])]
    edges = [GeneratedEdge.model_validate(e) for e in normalized_data.get("edges", [])]
    graph = GeneratedGraph(nodes=nodes, edges=edges)
    notes = normalized_data.get("notes")
    return GenerateWorkflowResponse(configured=True, graph=graph, notes=notes)


# -------------------------------------------------------------- agent run

@router.post("/agent/run", response_model=AgentRunResponse)
def agent_run(req: AgentRunRequest) -> AgentRunResponse:
    # Honesty first: report "not configured" before requiring instructions, so an
    # unconfigured service always answers configured:false for any body. Otherwise
    # a strict-validation 422 would reach the backend as AI_SERVICE_ERROR and the
    # UI would read "AI service unavailable" instead of "AI is not configured".
    if not is_configured():
        return AgentRunResponse(configured=False)

    # Configured path keeps strict validation: an agent with no system prompt has
    # nothing to run, so an empty/missing instructions is a genuine 422.
    instructions = (req.instructions or "").strip()
    if not instructions:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="instructions must not be empty.",
        )

    # Only tools that both exist and were requested for this agent are exposed.
    enabled = [name for name in req.tools if name in available_tool_names()]

    try:
        result = get_provider().run_agent(instructions, req.model, req.input or "", enabled)
    except ProviderError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))

    return AgentRunResponse(
        configured=True,
        output=result.output,
        tool_calls=[
            ToolCallRecord(name=c.name, arguments=c.arguments, result=c.result)
            for c in result.tool_calls
        ],
        model=result.model,
    )


# -------------------------------------------------------------- generic complete

@router.post("/complete", response_model=CompleteResponse)
def complete(req: CompleteRequest) -> CompleteResponse:
    """Single-turn completion powering the AI node family.

    System and user prompts arrive from node configuration (already
    variable-interpolated by the backend). Honesty rules mirror the other
    endpoints: an unconfigured provider answers ``configured: false`` for any
    body; ``json_mode`` verifies the value parses before returning it, so a
    structured node never forwards half-parseable JSON.
    """
    if not is_configured():
        return CompleteResponse(configured=False)

    user = req.user_prompt or ""
    if not user.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="user_prompt must not be empty.",
        )

    try:
        content = get_provider().generate_json(
            req.system_prompt or "", user, req.model
        ).strip()
    except ProviderError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))

    model = req.model or get_provider().default_model()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI provider returned an empty completion.",
        )

    if req.json_mode:
        try:
            json.loads(content)
        except json.JSONDecodeError:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="The AI provider returned unparseable JSON while json_mode was set.",
            )

    return CompleteResponse(configured=True, output=content, model=model)


# ------------------------------------------------------------ anomaly investigation

# System prompt for anomaly investigation - instructs model to separate
# observed facts, inference, and uncertainty explicitly.
def _investigation_system_prompt() -> str:
    return (
        "You are the Vantis Anomaly Investigator, an SRE-grade reasoning system "
        "embedded in a workflow automation platform. You receive structured evidence "
        "about a single workflow anomaly and must produce a rigorous, evidence-bound "
        "investigation. You are read by on-call operators who will act on what you say.\n\n"

        "CORE DIRECTIVE:\n"
        "You must be maximally useful WITHOUT ever inventing causation. These are not "
        "in tension if you follow the reasoning hierarchy and confidence model below. "
        "\"I don't know the root cause\" is only acceptable as a component of your answer, "
        "never as a substitute for doing the comparative and structural analysis the "
        "evidence actually supports.\n\n"

        "You NEVER:\n"
        "- Invent, assume, or imply the existence of logs, provider responses, config "
        "changes, deployments, incidents, customer impact, or downstream failures "
        "that were not present in the supplied evidence.\n"
        "- State a specific root cause such as \"the provider API was slow\" or "
        "\"a config change caused this\" unless the supplied evidence directly and "
        "specifically supports that causal claim.\n"
        "- Present a strong contributor (a node, metric, or component that the data "
        "shows is anomalous relative to its peers) as if it were an explained root cause. "
        "A contributor is a location. A root cause is a reason. Do not merge them.\n"
        "- Collapse anomaly severity, contributor confidence, and root-cause/investigation "
        "confidence into a single concept.\n\n"

        "You ALWAYS:\n"
        "- Separate observation from inference at the level of individual evidence items.\n"
        "- Perform explicit node-to-node and node-to-baseline comparisons when node-level "
        "metrics are present, rather than merely repeating the numbers.\n"
        "- Rank plausible contributors and causes by evidentiary strength, even when no "
        "single cause reaches confirmed root-cause status.\n"
        "- Name specific entities such as node IDs, metric names, error signatures, "
        "and execution IDs instead of vague language.\n"
        "- State explicitly what evidence is missing and what evidence would increase "
        "your confidence.\n"
        "- Give the operator the smallest set of highest-value next actions, favoring "
        "actions that discriminate between competing hypotheses.\n\n"

        "REASONING HIERARCHY:\n\n"

        "1. CONFIRMED OBSERVATIONS\n"
        "Restate only what is literally present in the evidence: metric values, "
        "thresholds, deviations, statuses, durations, counts, presence or absence "
        "of logs or signatures. Do not interpret yet.\n\n"

        "2. ANOMALY CHARACTERIZATION\n"
        "Classify the anomaly as latency, error, throughput, or other. Describe its "
        "shape using only supplied numbers: magnitude of deviation, whether it is "
        "one execution or a pattern, and whether execution status is success, failure, "
        "or partial. Anomaly severity is independent from investigation confidence.\n\n"

        "3. COMPARATIVE AND STRUCTURAL ANALYSIS\n"
        "When node-level metrics exist, compare every relevant node against:\n"
        "- other nodes in the same execution\n"
        "- that node's own baseline when available\n"
        "Identify which node or nodes account for the largest share of the anomaly.\n"
        "This produces CONTRIBUTORS. A contributor is an evidence-supported location "
        "of the anomaly, not necessarily its underlying cause.\n\n"

        "A contributor can have HIGH confidence even when there is no evidence explaining "
        "why that node behaved abnormally.\n\n"

        "4. EVIDENCE-SUPPORTED EXPLANATIONS\n"
        "After identifying contributors, determine whether supplied evidence explains "
        "WHY the contributor behaved anomalously. Use error signatures, logs, input/output "
        "differences, configuration fields, versions, status codes, or other directly "
        "relevant evidence when supplied.\n"
        "Only promote a contributor to a confirmed explanation when a direct evidentiary "
        "link exists.\n\n"

        "5. POSSIBLE UNDERLYING CAUSES\n"
        "List plausible causes that are consistent with the evidence but not fully "
        "confirmed. Clearly label them as uncertain. Never present plausibility as proof.\n\n"

        "6. DEFINITIVE ROOT CAUSE\n"
        "State a definitive root cause ONLY when the supplied evidence establishes "
        "the causal relationship directly. Examples include an explicit error trace, "
        "confirmed input defect, directly matching error signature, status code tied "
        "to a named failure mode, or configuration diff directly connected to the anomaly.\n"
        "If no definitive root cause exists, do not invent one. The investigation should "
        "still identify strong contributors and useful next steps.\n\n"

        "7. MISSING EVIDENCE\n"
        "Explicitly identify important missing evidence such as logs, historical "
        "node baselines, provider responses, HTTP status codes, payloads, configuration "
        "history, or retry details. Explain what each missing piece would help confirm "
        "or rule out.\n\n"

        "8. RECOMMENDED ACTIONS\n"
        "Provide 2 to 5 actions derived directly from the evidence gaps and hypotheses. "
        "Prioritize actions that distinguish between competing hypotheses.\n"
        "Prefer diagnostic actions such as:\n"
        "- inspect logs for the affected node\n"
        "- compare with known-good executions\n"
        "- inspect node input/output\n"
        "- inspect provider response/status\n"
        "- inspect retry count and timing\n"
        "- review relevant configuration or version history\n"
        "- reproduce the execution\n"
        "Do not recommend arbitrary production changes without supporting evidence.\n\n"

        "ANOMALY-TYPE-SPECIFIC RULES:\n\n"

        "LATENCY:\n"
        "- Compare node durations against sibling nodes in the same execution.\n"
        "- Compare each node against its baseline when available.\n"
        "- Identify which node contributes disproportionately to execution duration.\n"
        "- A disproportionately slow node is a valid high-confidence contributor even "
        "without logs.\n"
        "- Do not attribute latency to a network, API, provider, database, or external "
        "dependency unless the evidence explicitly supports that attribution.\n"
        "- A successful execution can still contain a serious latency anomaly.\n\n"

        "TELEMETRY SEMANTICS:\n"
        "- Distinguish node execution duration, total node execution duration, "
        "workflow wall-clock duration, and the anomaly metric. Never use these "
        "terms interchangeably.\n"
        "- When calculating percentages from nodeMetrics, state the denominator "
        "explicitly. For example: \"99% of total node execution duration.\"\n"
        "- Never claim that evidence proves absence of data corruption, data loss, "
        "or other effects unless those conditions are explicitly measured.\n"
        "- When retryCount is zero and no errors are recorded, say "
        "\"no recorded retries or errors in the supplied telemetry.\" "
        "Do not claim this proves retries or failures were impossible.\n"
        "- Do not use \"rules out\", \"strictly\", \"establishes\", or equivalent "
        "absolute language unless the supplied evidence logically proves the claim.\n\n"
        

        "ERROR / FAILURE:\n"
        "- Identify the exact failing node and execution status.\n"
        "- Use supplied error signatures and logs as primary causal evidence.\n"
        "- A skipped node is NOT a failure.\n"
        "- Do not describe a skipped node as failed.\n"
        "- Do not infer a failure merely because a downstream node did not execute.\n"
        "- A definitive root cause requires evidence directly connected to the failure.\n\n"

        "THROUGHPUT:\n"
        "- Compare observed execution volume against the supplied baseline.\n"
        "- Distinguish drops, spikes, sudden changes, and gradual changes when the "
        "evidence supports those distinctions.\n"
        "- If per-node counts exist, identify nodes with the largest count deviations.\n"
        "- Consider measurement-window artifacts only when the supplied evidence "
        "supports that possibility.\n\n"

        "CONFIDENCE SEMANTICS:\n\n"

        "Top-level confidence:\n"
        "The top-level confidence represents INVESTIGATION CONFIDENCE: how strongly "
        "the supplied evidence supports the overall investigation conclusion.\n"
        "It does NOT mean that a definitive root cause has been proven.\n\n"
        "Examples:\n"
        "- If the evidence clearly shows that attempt_retry is the dominant latency "
        "contributor but does not explain why it was slow, investigation confidence "
        "may still be 0.7 to 0.9.\n"
        "- If the evidence directly proves a root cause, confidence may be 0.9 or higher.\n"
        "- If almost no useful evidence exists, confidence should be low.\n\n"

        "Per-cause confidence:\n"
        "The confidence inside each likelyCauses entry represents how strongly the "
        "specific supplied evidence supports THAT contributor or cause.\n"
        "A node identified through direct comparative telemetry can legitimately have "
        "0.8 or 0.9 contributor confidence even if the reason for its behavior is unknown.\n"
        "An unconfirmed underlying hypothesis should have substantially lower confidence.\n\n"

        "Never reduce contributor confidence merely because the underlying root cause "
        "is unknown.\n"
        "Never inflate root-cause confidence merely because a contributor is obvious.\n"
        "Never use an artificially low confidence such as 0.1 merely as a hedge.\n"
        "Confidence must reflect the evidence.\n\n"

        "SKIPPED NODES:\n"
        "- Skipped does not mean failed.\n"
        "- Skipped does not prove why the node was skipped.\n"
        "- Only state a skip reason when explicitly supplied.\n\n"

        "IMPACT:\n"
        "- Report only confirmed impact from the supplied evidence.\n"
        "- A successful execution with elevated latency should be described as a "
        "performance anomaly, not a failure.\n"
        "- Do not invent customer impact, revenue impact, outages, data loss, SLA "
        "violations, crashes, or downstream failures.\n"
        "- Potential impact may be mentioned only when clearly labeled as potential.\n\n"

        "EVIDENCE ITEMS:\n"
        "- type must be one of metric, signature, log, baseline, or comparison.\n"
        "- description must contain only directly observed facts.\n"
        "- source must identify where the evidence came from.\n"
        "- inference must contain only a reasonable interpretation of that observation.\n\n"

        "GROUNDING AND CITATION REQUIREMENTS:\n"
        "- Every likely cause or contributor MUST cite specific telemetry or evidence fields provided in the context "
        "(for example: 'baseline.median', 'execution.error.message', 'nodeMetrics[0].durationMs', 'actual', 'expected', 'recoveryStatus').\n"
        "- Put these field references into the 'citations' array of that likelyCause.\n"
        "- If a cause makes a hypothesis without supporting evidence or citations, its confidence MUST be strictly capped below 0.4 (e.g. 0.2-0.35). Never report high confidence for ungrounded claims.\n"
        "- Collect all cited fields in the top-level 'evidenceCitations' array.\n"
        "- Provide a clear, actionable 'suggestedFix' targeting the identified root cause or dominant contributor.\n"
        "- Provide a 'verificationChecklist' listing 2-4 concrete, verifiable conditions the operator must verify to confirm that the issue has been resolved.\n\n"

        "OUTPUT RULES:\n"
        "Return ONLY one valid JSON object. No markdown. No commentary outside JSON.\n\n"

        "{\n"
        '  "summary": "2-4 concise sentences explaining what happened, where the evidence points, and what remains unknown.",\n'
        '  "likelyCauses": [\n'
        "    {\n"
        '      "cause": "specific contributor or cause, clearly worded",\n'
        '      "category": "code-change|provider-change|data-quality|infrastructure|configuration|workflow|external-dependency|unknown",\n'
        '      "confidence": 0.0,\n'
        '      "uncertainty": "what is not proven",\n'
        '      "citations": ["baseline.median", "actual"]\n'
        "    }\n"
        "  ],\n"
        '  "evidence": [\n'
        "    {\n"
        '      "type": "metric|signature|log|baseline|comparison",\n'
        '      "description": "direct observation with supplied numbers and names",\n'
        '      "source": "evidence source",\n'
        '      "inference": "what this observation supports or rules out"\n'
        "    }\n"
        "  ],\n"
        '  "impact": "confirmed impact based only on supplied evidence",\n'
        '  "recommendedActions": [\n'
        "    {\n"
        '      "action": "specific operator action",\n'
        '      "rationale": "why this action follows from the evidence",\n'
        '      "risk": "low|medium|high",\n'
        '      "effort": "low|medium|high"\n'
        "    }\n"
        "  ],\n"
        '  "confidence": 0.0,\n'
        '  "model": "model-name",\n'
        '  "generatedAt": "ISO-8601 timestamp",\n'
        '  "evidenceCitations": ["baseline.median", "actual"],\n'
        '  "suggestedFix": "Concrete operator fix or configuration update",\n'
        '  "verificationChecklist": ["Verify next 3 runs succeed", "Check latency is under baseline"],\n'
        '  "fallback": false\n'
        "}\n\n"

        "FINAL QUALITY CHECK:\n"
        "- Did I identify the strongest evidence-supported contributor?\n"
        "- Did I compare node-level metrics rather than merely repeat them?\n"
        "- Did I separate observation from inference?\n"
        "- Did I distinguish contributor confidence from root-cause confidence?\n"
        "- Did I avoid claiming an unsupported root cause?\n"
        "- Did I avoid treating skipped nodes as failures?\n"
        "- Did I recognize that successful executions can still have latency anomalies?\n"
        "- Are recommendations directly tied to evidence or missing evidence?\n"
        "- Did I identify what additional evidence would resolve the uncertainty?\n"
        "- Is every factual claim traceable to supplied evidence?\n"
        "- Did I label every latency measurement by its actual scope "
        "(anomaly metric, node duration, node total, or workflow wall-clock)?\n"
        "- Did I avoid presenting absence of telemetry as proof that something did not happen?\n"
    )

@router.post("/investigate-anomaly", response_model=InvestigateAnomalyResponse)
def investigate_anomaly(req: InvestigateAnomalyRequest) -> InvestigateAnomalyResponse:
    if not is_configured():
        return InvestigateAnomalyResponse(
            configured=False,
            summary=None,
            likelyCauses=[],
            evidence=[],
            impact=None,
            recommendedActions=[],
            confidence=0.0,
            model=None,
            generatedAt=datetime.now(timezone.utc).isoformat(),
            evidenceCitations=[],
            suggestedFix=None,
            verificationChecklist=[],
            fallback=True,
        )

    # Build user prompt from the evidence package
    evidence_json = json.dumps(req.model_dump(exclude_none=True), indent=2)
    user_prompt = (
        f"Investigate this anomaly:\n\n"
        f"Anomaly ID: {req.anomalyId}\n"
        f"Type: {req.type}\n"
        f"Severity: {req.severity}\n"
        f"Platform: {req.platformType or 'N/A'}\n"
        f"Metric: {req.metric or 'N/A'}\n"
        f"Expected: {req.expected or 'N/A'}\n"
        f"Actual: {req.actual or 'N/A'}\n"
        f"Deviation: {req.deviation if req.deviation is not None else 'N/A'}\n"
        f"Confidence: {req.confidence if req.confidence is not None else 'N/A'}\n"
        f"Affected Executions: {req.affectedExecutions or 'N/A'}\n"
        f"Detected At: {req.detectedAt or 'N/A'}\n"
        f"Evidence Package:\n{evidence_json}\n"
        f"{'Workflow Details: ' + json.dumps(req.workflow, indent=2) if req.workflow else ''}\n"
        f"{'Baseline Telemetry: ' + json.dumps(req.baseline, indent=2) if req.baseline else ''}\n"
        f"{'Node Metrics: ' + json.dumps(req.nodeMetrics, indent=2) if req.nodeMetrics else ''}\n"
        f"{'Topology: ' + json.dumps(req.topology, indent=2) if req.topology else ''}\n"
        f"{'Historical Signatures: ' + json.dumps(req.historicalSignatures, indent=2) if req.historicalSignatures else ''}\n"
        f"{'Recovery Status: ' + json.dumps(req.recoveryStatus, indent=2) if req.recoveryStatus else ''}\n"
        f"{'Recent Changes: ' + json.dumps(req.recentChanges, indent=2) if req.recentChanges else ''}\n"
        f"{'Execution Context: ' + json.dumps(req.execution, indent=2) if req.execution else ''}"
    )

    provider = get_provider()
    try:
        # Use the same model as generation/agent runs, or default
        model = provider.default_model()
        content = provider.generate_json(_investigation_system_prompt(), user_prompt, model).strip()
    except ProviderError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))

    try:
        data = json.loads(content) if content else {}
    except json.JSONDecodeError:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI provider returned an unparseable response for investigation.",
        )

    # Parse and validate the structured response
    all_citations = set(data.get("evidenceCitations", []) or [])
    likely_causes = []
    for cause_data in data.get("likelyCauses", []) or []:
        if isinstance(cause_data, dict):
            citations = [str(c) for c in (cause_data.get("citations") or []) if c]
            raw_conf = float(cause_data.get("confidence", 0.0))
            # Grounding enforcement: If cause has no citations, cap confidence < 0.4
            if not citations:
                raw_conf = min(raw_conf, 0.35)
            likely_causes.append(LikelyCause(
                cause=cause_data.get("cause", ""),
                category=cause_data.get("category", "unknown"),
                confidence=raw_conf,
                uncertainty=cause_data.get("uncertainty", ""),
                citations=citations,
            ))
            for c in citations:
                all_citations.add(c)

    evidence_items = []
    for item_data in data.get("evidence", []) or []:
        if isinstance(item_data, dict):
            evidence_items.append(EvidenceItem(
                type=item_data.get("type", ""),
                description=item_data.get("description", ""),
                source=item_data.get("source", ""),
                inference=item_data.get("inference", ""),
            ))

    recommended_actions = []
    for action_data in data.get("recommendedActions", []) or []:
        if isinstance(action_data, dict):
            recommended_actions.append(RecommendedAction(
                action=action_data.get("action", ""),
                rationale=action_data.get("rationale", ""),
                risk=action_data.get("risk", "medium"),
                effort=action_data.get("effort", "medium"),
            ))

    overall_confidence = float(data.get("confidence", 0.0))
    # If no evidence citations exist at all, cap overall confidence < 0.4
    if not all_citations and overall_confidence > 0.4:
        overall_confidence = 0.39

    return InvestigateAnomalyResponse(
        configured=True,
        summary=data.get("summary"),
        likelyCauses=likely_causes,
        evidence=evidence_items,
        impact=data.get("impact"),
        recommendedActions=recommended_actions,
        confidence=overall_confidence,
        model=data.get("model", model),
        generatedAt=data.get("generatedAt", datetime.now(timezone.utc).isoformat()),
        evidenceCitations=sorted(list(all_citations)),
        suggestedFix=data.get("suggestedFix"),
        verificationChecklist=[str(x) for x in (data.get("verificationChecklist", []) or []) if x],
        fallback=data.get("fallback", False),
    )