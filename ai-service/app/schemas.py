"""Request/response schemas for the AI endpoints.

These are the wire contract between the Spring backend and this service. Field
names are snake_case on the Python side; the backend maps them explicitly, so
there is no shared casing requirement. Every response carries a ``configured``
flag so an unconfigured provider is reported honestly rather than faked.
"""

from typing import Any

from pydantic import BaseModel, Field


# ------------------------------------------------------------- generate-workflow


class GenerateWorkflowRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=4000)


class GeneratedNode(BaseModel):
    id: str
    type: str
    label: str | None = None
    position: dict[str, Any] | None = None
    data: dict[str, Any] | None = None
    config: dict[str, Any] = Field(default_factory=dict)


class GeneratedEdge(BaseModel):
    id: str | None = None
    source: str
    target: str
    source_handle: str | None = None
    sourceHandle: str | None = None


class GeneratedGraph(BaseModel):
    nodes: list[GeneratedNode] = Field(default_factory=list)
    edges: list[GeneratedEdge] = Field(default_factory=list)


class GenerateWorkflowResponse(BaseModel):
    configured: bool
    graph: GeneratedGraph | None = None
    notes: str | None = None


# -------------------------------------------------------------------- agent-run


class AgentRunRequest(BaseModel):
    # `instructions` is optional at the wire level and its non-emptiness is
    # enforced inside the handler, *after* the not-configured short-circuit. That
    # ordering is deliberate: an unconfigured service must answer
    # ``configured: false`` (an honest "not configured") for any body, rather than
    # raising a 422 the backend would surface as "AI service unavailable". Unlike
    # the generate-workflow ``prompt`` (genuine per-request user input, validated
    # strictly), these instructions come from a saved agent's server-side config.
    instructions: str | None = Field(default=None, max_length=8000)
    model: str | None = None
    tools: list[str] = Field(default_factory=list)
    input: str | None = None


class ToolCallRecord(BaseModel):
    """One tool invocation the model made during a run, for transparency."""

    name: str
    arguments: dict[str, Any]
    result: str


class AgentRunResponse(BaseModel):
    configured: bool
    output: str | None = None
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    model: str | None = None


# -------------------------------------------------------------- generic complete


class CompleteRequest(BaseModel):
    """A generic single-turn completion used by the AI node family.

    Unlike ``AgentRunRequest`` these prompts come from a node's own system/input
    configuration and may embed variable-resolved payloads, so both may be up to
    16k characters. ``json_mode`` asks the provider to produce a strict JSON
    value; the endpoint then verifies it parses, so a ``json_generator``-style
    node can rely on well-formed output instead of re-parsing text.
    """

    system_prompt: str = Field(default="", max_length=16000)
    user_prompt: str = Field(default="", max_length=16000)
    model: str | None = None
    json_mode: bool = False


class CompleteResponse(BaseModel):
    configured: bool
    output: str | None = None
    model: str | None = None


# ------------------------------------------------------------ anomaly investigation


class InvestigateAnomalyRequest(BaseModel):
    """Structured evidence package from the backend for on-demand investigation.

    Contains only statistical aggregates, structural signatures, and anomaly
    metadata — never raw payloads, secrets, or credentials.
    """

    anomalyId: str
    type: str
    severity: str
    metric: str | None = None
    expected: str | None = None
    actual: str | None = None
    deviation: float | None = None
    confidence: float | None = None
    affectedExecutions: int | None = None
    evidence: dict[str, Any] | None = None
    detectedAt: str | None = None
    execution: dict[str, Any] | None = None


class LikelyCause(BaseModel):
    cause: str
    category: str
    confidence: float
    uncertainty: str


class EvidenceItem(BaseModel):
    type: str
    description: str
    source: str
    inference: str


class RecommendedAction(BaseModel):
    action: str
    rationale: str
    risk: str
    effort: str


class InvestigateAnomalyResponse(BaseModel):
    configured: bool
    summary: str | None = None
    likelyCauses: list[LikelyCause] = Field(default_factory=list)
    evidence: list[EvidenceItem] = Field(default_factory=list)
    impact: str | None = None
    recommendedActions: list[RecommendedAction] = Field(default_factory=list)
    confidence: float
    model: str | None = None
    generatedAt: str  # ISO-8601 timestamp from the AI service