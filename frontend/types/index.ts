/**
 * Wire types for the FlowOps M1 API (auth + multi-tenancy).
 *
 * These mirror the frozen API contract exactly — camelCase everywhere, UUIDs as
 * lowercase 36-char strings, timestamps as ISO-8601 UTC with milliseconds and a
 * `Z` suffix. Do not add fields that the backend does not send: request bodies
 * are validated with `fail-on-unknown-properties: true`, so an extra key on the
 * way out is a `400 MALFORMED_REQUEST`.
 */

/* ------------------------------------------------------------------ errors */

export type ErrorCode =
  | "VALIDATION_ERROR"
  | "MALFORMED_REQUEST"
  | "UNSUPPORTED_MEDIA_TYPE"
  | "PAYLOAD_TOO_LARGE"
  | "AUTHENTICATION_REQUIRED"
  | "INVALID_CREDENTIALS"
  | "TOKEN_EXPIRED"
  | "TOKEN_INVALID"
  | "REFRESH_TOKEN_INVALID"
  | "RESET_TOKEN_INVALID"
  | "FORBIDDEN_ROLE"
  | "NO_ORGANIZATION_CONTEXT"
  | "ORGANIZATION_NOT_FOUND"
  | "MEMBER_NOT_FOUND"
  | "CANNOT_MODIFY_SELF"
  | "LAST_OWNER"
  | "INVITATION_NOT_FOUND"
  | "INVITATION_INVALID"
  | "INVALID_PASSWORD"
  | "WORKFLOW_NOT_FOUND"
  | "WORKFLOW_INVALID"
  | "WORKFLOW_NOT_PUBLISHED"
  | "EXECUTION_NOT_FOUND"
  | "EXECUTION_NOT_RETRYABLE"
  | "APPROVAL_NOT_PENDING"
  | "AI_AGENT_NOT_FOUND"
  | "AI_NOT_CONFIGURED"
  | "AI_SERVICE_ERROR"
  | "AI_GENERATION_FAILED"
  | "INTEGRATION_NOT_FOUND"
  | "INTEGRATION_INVALID"
  | "NOTIFICATION_NOT_FOUND"
  | "TEMPLATE_NOT_FOUND"
  | "ANOMALY_NOT_FOUND"
  | "NOT_FOUND"
  | "METHOD_NOT_ALLOWED"
  | "EMAIL_ALREADY_REGISTERED"
  | "RATE_LIMITED"
  | "CONCURRENCY_LIMIT_EXCEEDED"
  | "CIRCUIT_BREAKER_OPEN"
  | "DEAD_LETTER_NOT_FOUND"
  | "INCIDENT_NOT_FOUND"
  | "PLAN_LIMIT_EXCEEDED"
  | "BILLING_DISABLED"
  | "INTERNAL_ERROR";

export interface FieldError {
  field: string;
  message: string;
}

/** Structured machine-readable detail for PLAN_LIMIT_EXCEEDED errors. */
export interface PlanLimitDetail {
  limitKey: string;
  current: number;
  max: number;
  planId: string;
  suggestedPlan: string;
}

/** The single error envelope returned by every non-2xx response. */
export interface ApiError {
  error: {
    code: ErrorCode;
    message: string;
    status: number;
    timestamp: string;
    path: string;
    requestId: string;
    /** Key is always present; an array only when `code === "VALIDATION_ERROR"`. */
    fieldErrors: FieldError[] | null;
    /** Present when code === "PLAN_LIMIT_EXCEEDED" */
    details?: PlanLimitDetail | null;
  };
}

/* --------------------------------------------------------------- resources */

/** Ordered `OWNER > ADMIN > MEMBER > VIEWER`. Role checks compare rank. */
export type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface User {
  id: string;
  /** Always the normalized (trimmed, lowercased) form. */
  email: string;
  fullName: string;
  /** Always `null` in M1 — render initials from `fullName` instead. */
  avatarUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Organization {
  id: string;
  /** As typed by the user (trimmed). Not unique across tenants. */
  name: string;
  /** Server-derived and globally unique. Immutable across renames. */
  slug: string;
  createdAt: string;
  updatedAt: string;
}

/** Denormalized org-switcher payload. Sorted `joinedAt ASC, organizationId ASC`. */
export interface Membership {
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  role: Role;
  joinedAt: string;
}

/** A row of the current organization's member list. */
export interface OrganizationMember {
  userId: string;
  email: string;
  fullName: string;
  avatarUrl: string | null;
  role: Role;
  joinedAt: string;
}

/* --------------------------------------------------------------- responses */

/** Identity + tenant snapshot. Returned by `GET /api/auth/me`. */
export interface SessionResponse {
  user: User;
  currentOrganization: Organization;
  currentRole: Role;
  memberships: Membership[];
}

/**
 * `SessionResponse` plus the three token fields. Returned by register, login,
 * refresh and switch-org — one shape, one "hydrate auth state" function.
 *
 * There is deliberately no `refreshToken` field: the refresh token only ever
 * exists in the HttpOnly `flowops_refresh` cookie.
 */
export interface AuthResponse extends SessionResponse {
  accessToken: string;
  tokenType: "Bearer";
  /** Access-token lifetime in seconds (900), not a timestamp. */
  expiresIn: number;
}

/** `GET /api/organizations` — always an object, never a bare array. */
export interface OrganizationsResponse {
  memberships: Membership[];
}

/** `GET /api/organizations/current` and `PATCH /api/organizations/current`. */
export interface CurrentOrganizationResponse {
  organization: Organization;
  role: Role;
  memberCount: number;
}

/** `POST /api/organizations` — creating an org does not switch you into it. */
export interface CreateOrganizationResponse {
  organization: Organization;
  role: Role;
}

/** `GET /api/organizations/current/members`. Sorted `joinedAt ASC, userId ASC`. */
export interface OrganizationMembersResponse {
  members: OrganizationMember[];
}

/* ------------------------------------------------------- team / invitations */

/** Lifecycle of an invitation. */
export type InvitationStatus = "PENDING" | "ACCEPTED" | "REVOKED";

/**
 * A tokened invitation to join the current org, as seen on the management surface.
 * The raw token is NEVER returned here — only its non-secret last-4 `tokenHint`.
 * The full acceptance token is surfaced exactly once, at create time
 * (`InvitationSecret`), and can never be recovered afterwards.
 */
export interface Invitation {
  id: string;
  email: string;
  role: Role;
  status: InvitationStatus;
  tokenHint: string | null;
  expiresAt: string;
  createdAt: string;
}

/** `GET /api/organizations/current/invitations` — always an object. */
export interface InvitationsResponse {
  invitations: Invitation[];
}

/**
 * `POST /api/organizations/current/invitations` — the ONE-TIME response carrying
 * the raw acceptance `token`. The frontend composes the shareable link from its
 * own origin; the token is shown only here and must be copied immediately.
 */
export interface InvitationSecret {
  token: string;
  email: string;
  role: Role;
  tokenHint: string;
  expiresAt: string;
}

/** `POST /api/organizations/current/invitations`. Role may not be OWNER. */
export interface InviteMemberBody {
  email: string;
  role: Role;
}

/** `PATCH /api/organizations/current/members/{userId}`. */
export interface ChangeMemberRoleBody {
  role: Role;
}

/** `POST /api/invitations/accept` — redeem a token for the authenticated caller. */
export interface AcceptInvitationBody {
  token: string;
}

/** `POST /api/auth/change-password` — re-verifies the current password server-side. */
export interface ChangePasswordBody {
  currentPassword: string;
  newPassword: string;
}

/* --------------------------------------------------------------- workflows */

export type WorkflowStatus = "DRAFT" | "PUBLISHED" | "ARCHIVED";

export type NodeCategory = "TRIGGER" | "LOGIC" | "ACTION" | "AI";

/** One configurable field on a node's config panel (mirrors backend ConfigField). */
export interface ConfigField {
  key: string;
  label: string;
  /** Widget hint: string | text | number | boolean | select | json | code. */
  type: string;
  required: boolean;
  options: string[];
  help: string | null;
  placeholder: string | null;
}

/** A node type from the registry. `GET /api/node-types`. */
export interface NodeDefinition {
  type: string;
  label: string;
  description: string;
  category: NodeCategory;
  /** lucide-react icon name. */
  icon: string;
  trigger: boolean;
  maxInputs: number;
  outputs: string[];
  configFields: ConfigField[];
}

export interface NodeTypesResponse {
  nodeTypes: NodeDefinition[];
}

/* ----- graph -----
 * The graph is stored verbatim as jsonb. It uses React Flow's node/edge shape;
 * the backend only reads `id`, `type`, `data.config` on nodes and the endpoints
 * on edges, and ignores unknown keys (position, selection, styling). */

export interface GraphNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: {
    label?: string;
    config?: Record<string, unknown>;
    [key: string]: unknown;
  };
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

export interface WorkflowGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/** Row in the workflows list. `GET /api/workflows`. */
export interface WorkflowSummary {
  id: string;
  name: string;
  description: string | null;
  status: WorkflowStatus;
  latestVersion: number | null;
  nodeCount: number;
  createdAt: string;
  updatedAt: string;
}

/** Full workflow with its editable draft graph. */
export interface WorkflowDetail {
  id: string;
  name: string;
  description: string | null;
  status: WorkflowStatus;
  latestVersion: number | null;
  graph: WorkflowGraph;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowsResponse {
  workflows: WorkflowSummary[];
}

export type ValidationSeverity = "ERROR" | "WARNING";

export interface ValidationIssue {
  severity: ValidationSeverity;
  code: string;
  message: string;
  nodeId: string | null;
  edgeId: string | null;
}

export interface ValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
}

/** A published, immutable snapshot. */
export interface WorkflowVersion {
  id: string;
  versionNumber: number;
  note: string | null;
  /** Present only on the single-version detail read; null in the list. */
  graph: WorkflowGraph | null;
  publishedBy: string;
  createdAt: string;
}

export interface WorkflowVersionsResponse {
  versions: WorkflowVersion[];
}

/** `POST /api/workflows/{id}/publish` → always 200. */
export interface PublishResult {
  published: boolean;
  version: WorkflowVersion | null;
  validation: ValidationResult;
}

/* -------------------------------------------------------------- executions */

/** Lifecycle of a run. `WAITING` is a Human Approval pause; the rest are ordinary. */
export type ExecutionStatus =
  | "QUEUED"
  | "RUNNING"
  | "WAITING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELED";

/** Whether a status is settled — the run will not change without a retry. */
export const TERMINAL_EXECUTION_STATUSES: ReadonlySet<ExecutionStatus> =
  new Set<ExecutionStatus>(["SUCCEEDED", "FAILED", "CANCELED"]);

/** Per-node lifecycle. `SKIPPED` is a node on an untaken branch. */
export type NodeRunStatus =
  | "PENDING"
  | "RUNNING"
  | "WAITING"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED";

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

/** How a run was started. Derived from the graph's trigger node, never client-set. */
export type TriggerType = "MANUAL" | "WEBHOOK";

/**
 * A run as it appears in the executions list and in live `execution` SSE frames.
 * `workflowName` is resolved on reads but sent as `null` on the stream snapshot —
 * merge stream frames onto the detail fetch, never overwriting a known name.
 */
export interface ExecutionSummary {
  id: string;
  workflowId: string;
  workflowName: string | null;
  versionNumber: number;
  status: ExecutionStatus;
  triggerType: TriggerType;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
}

/**
 * One node's state within a run. `input`/`output` are the interpolated JSON the
 * node consumed and produced; `activeHandles` are the output ports the run took
 * (e.g. `["true"]` on a condition, `["approved"]` on an approval).
 */
export interface ExecutionNodeState {
  id: string;
  /** Graph node id — stable across attempts; matches `GraphNode.id`. */
  nodeId: string;
  nodeType: string;
  label: string | null;
  status: NodeRunStatus;
  /** 1-based; climbs on retry/backoff. */
  attempt: number;
  input: unknown | null;
  output: unknown | null;
  activeHandles: string[];
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  attempts?: NodeExecutionAttemptState[];
}

export interface NodeExecutionAttemptState {
  id: string;
  executionId: string;
  nodeId: string;
  attemptNumber: number;
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT";
  error: string | null;
  durationMs: number | null;
  startedAt: string;
  finishedAt: string | null;
}

/** One line in a run's log. `nodeId` is null for execution-level lines. */
export interface ExecutionLogEntry {
  id: string;
  nodeId: string | null;
  level: LogLevel;
  message: string;
  /** Monotonic per run — the dedup + ordering key for merging live frames. */
  seq: number;
  createdAt: string;
}

/**
 * Full run detail. Returned by `GET /api/executions/{id}`, the run/decide/retry
 * mutations, and mirrored (minus `workflowName`/`triggerPayload`) by the SSE
 * snapshot.
 */
export interface ExecutionDetail {
  id: string;
  workflowId: string;
  workflowName: string | null;
  workflowVersionId: string;
  versionNumber: number;
  status: ExecutionStatus;
  triggerType: TriggerType;
  triggerPayload: unknown | null;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  nodes: ExecutionNodeState[];
  logs: ExecutionLogEntry[];
}

/** `GET /api/executions` — always an object, never a bare array. */
export interface ExecutionsResponse {
  executions: ExecutionSummary[];
}

/* ----------------------------------------------------- external executions */

/**
 * Lifecycle status reported by an external workflow provider such as n8n.
 */
export type ExternalExecutionStatus =
  | "RUNNING"
  | "WAITING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELED"
  | "SKIPPED";

/**
 * One externally monitored execution event.
 *
 * FlowOps stores provider execution telemetry at the step/node level.
 * Therefore one external execution can contain multiple events with the same
 * `executionExternalId`.
 */
export interface ExecutionEvent {
  id: string;
  organizationId: string;
  workflowId: string;
  source: string;
  workflowExternalId: string;
  executionExternalId: string;
  stepExternalId: string;
  stepName: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  retryCount: number | null;
  inputSize: number | null;
  outputSize: number | null;
  errorType: string | null;
  errorMessage: string | null;
  metadata: Record<string, unknown> | null;
  createdAt: string;
}

/** `GET /api/v1/execution-events` response. */
export interface ExecutionEventsResponse {
  events: ExecutionEvent[];
}

/** The four dashboard windows. `24h` buckets hourly; the rest bucket daily. */
export type StatsRange = "24h" | "7d" | "30d" | "90d";

/** Aggregate KPIs over a window. `successRate` is a 0–1 fraction of decided runs. */
export interface ExecutionStatsTotals {
  total: number;
  succeeded: number;
  failed: number;
  running: number;
  waiting: number;
  queued: number;
  canceled: number;
  successRate: number;
  avgDurationMs: number | null;
}

/** One bucket of the time series. `bucketStart` is the bucket's left edge (UTC). */
export interface ExecutionStatsPoint {
  bucketStart: string;
  total: number;
  succeeded: number;
  failed: number;
}

/** `GET /api/executions/stats?range=…` → KPIs + a fixed-width bucketed series. */
export interface ExecutionStats {
  range: string;
  since: string;
  totals: ExecutionStatsTotals;
  series: ExecutionStatsPoint[];
}

/* --------------------------------------------------------------- ai agents */

/**
 * The tools an AI agent may enable, mirrored from the AI service's allowlisted
 * registry (`ai-service/app/tools.py`). Only these names are ever accepted; the
 * backend and AI service both re-check, so an unknown name is silently ignored.
 */
export const AI_AGENT_TOOLS: {
  name: string;
  label: string;
  help: string;
}[] = [
  {
    name: "get_current_time",
    label: "Current time",
    help: "Return the current date and time (UTC, or a given IANA timezone).",
  },
  {
    name: "calculate",
    label: "Calculator",
    help: "Evaluate a basic arithmetic expression (+, −, ×, ÷, %, ^).",
  },
];

/** Row in the AI agents list. `GET /api/ai/agents`. */
export interface AiAgentSummary {
  id: string;
  name: string;
  description: string | null;
  model: string | null;
  tools: string[];
  createdAt: string;
  updatedAt: string;
}

/** Full agent including its instructions. `GET /api/ai/agents/{id}`. */
export interface AiAgentDetail {
  id: string;
  name: string;
  description: string | null;
  instructions: string;
  model: string | null;
  tools: string[];
  createdAt: string;
  updatedAt: string;
}

/** `GET /api/ai/agents` — always an object, never a bare array. */
export interface AiAgentsResponse {
  agents: AiAgentSummary[];
}

/**
 * Create / update payload. Update is a full replace (PATCH semantics on the
 * backend record), so always send every field the form owns. `name` and
 * `instructions` are required; blanks in the optional fields are dropped server
 * side.
 */
export interface AiAgentBody {
  name: string;
  description?: string;
  instructions: string;
  model?: string;
  tools?: string[];
}

/** One allowlisted tool the model invoked during a run, for transparency. */
export interface AgentToolCall {
  name: string;
  arguments: unknown;
  result: string;
}

/**
 * Result of running an agent (test console, mirrored by the `ai_agent` node).
 * `configured` is false when the AI service has no provider key: `output` is
 * then null and nothing is fabricated — an honest "not configured".
 */
export interface AgentRunResult {
  configured: boolean;
  output: string | null;
  toolCalls: AgentToolCall[];
  model: string | null;
}

/**
 * Result of "Create with AI". `graph` is a builder-ready workflow graph already
 * sanitized against the node registry; the canvas drops it in directly. It is
 * never run automatically. `notes` is optional model commentary.
 */
export interface GenerateWorkflowResult {
  graph: WorkflowGraph;
  notes: string | null;
}

/* ------------------------------------------------------------ integrations */

/** Connection state of an integration. */
export type IntegrationStatus = "connected" | "disconnected";

/**
 * A provider connection owned by the current org. There is exactly one row per
 * `(org, type)` — connecting again re-connects the same slot.
 *
 * The stored credential (e.g. the Slack incoming-webhook URL) is a secret and is
 * NEVER sent to the client: the API returns only a masked `hint` (the secret's
 * last few characters), which is `null` once disconnected. `type` is a bare
 * string — only `"slack"` is wired end-to-end in M5, but the backend owns the set.
 */
export interface Integration {
  id: string;
  type: string;
  name: string;
  status: IntegrationStatus;
  hint: string | null;
  createdAt: string;
  updatedAt: string;
}

/** `GET /api/integrations` — always an object, never a bare array. */
export interface IntegrationsResponse {
  integrations: Integration[];
}

/**
 * `POST /api/integrations` — connect (or re-connect) a provider by pasting its
 * secret. The plaintext is encrypted server-side and never returned.
 */
export interface ConnectIntegrationBody {
  type: string;
  webhookUrl: string;
}

/* --- provider-agnostic integration (M5+ workflow providers) ------------------ */

/**
 * Known provider type strings (wire values from IntegrationType.wire() on the
 * backend).  Typed as a union of known values plus `string` so that providers
 * added to the backend registry without a frontend update do not cause
 * TypeScript errors.  The backend is the authoritative source of accepted types.
 */
export type ProviderType =
  // Workflow automation
  | "n8n"
  | "make"
  | "zapier"
  | "temporal"
  // Developer / source control
  | "github"
  | "gitlab"
  | "vercel"
  | "sentry"
  | "pagerduty"
  | "linear"
  | "jira"
  | "asana"
  // CRM & Sales
  | "hubspot"
  | "salesforce"
  | "pipedrive"
  // Communication (delivery)
  | "slack"
  | "discord"
  | "teams"
  | "telegram"
  | "twilio"
  // Email (delivery)
  | "sendgrid"
  | "resend"
  | "smtp"
  | "email"
  // Generic / webhooks
  | "webhook"
  | "rest-api"
  | "rest_api"
  | "custom"
  // Cloud & Storage
  | "aws"
  | "s3"
  // Productivity
  | "notion"
  | "google-sheets"
  | "google_sheets"
  // Payments
  | "stripe"
  // AI & LLM
  | "anthropic"
  | "openai"
  // Forward-compatibility: allow any backend-defined type
  | (string & Record<never, never>);

/** Credential field definition returned by the provider catalog. */
export interface CredentialField {
  key: string;
  label: string;
  secret: boolean;
  placeholder: string;
  example: string;
  help: string;
}

/** Provider capability flags. */
export interface ProviderCapabilities {
  workflowDiscovery: boolean;
  executionHistory: boolean;
  nodeExecutionData: boolean;
  incrementalSync: boolean;
  webhooks: boolean;
  tracing: boolean;
}

export type ProviderCategory =
  | "COMMUNICATION"
  | "EMAIL"
  | "DEVELOPER"
  | "DATABASES"
  | "CLOUD"
  | "STORAGE"
  | "CRM"
  | "PROJECT_MANAGEMENT"
  | "PRODUCTIVITY"
  | "PAYMENTS"
  | "MARKETING"
  | "ANALYTICS"
  | "AI"
  | "AUTOMATION"
  | "UNIVERSAL";

/** A workflow provider in the catalog. */
export interface ProviderInfo {
  type: ProviderType;
  name: string;
  /**
   * Backend sends this once ProviderController.ProviderInfo includes the field.
   * Until then the client-side PROVIDER_META registry (lib/provider-meta.ts) is
   * used as a fallback.
   */
  category?: ProviderCategory;
  description: string;
  /** May be absent if the backend has not yet added the field to ProviderInfo. */
  icon?: string;
  available: boolean;
  capabilities: ProviderCapabilities;
  credentialFields: CredentialField[];
  docsUrl?: string;
  setupGuide?: string;
}

/** `GET /api/integrations/providers` — catalog of available workflow providers. */
export interface ProvidersResponse {
  providers: ProviderInfo[];
}

/** `POST /api/integrations` (generic) — connect a workflow provider. */
export interface ConnectProviderBody {
  type: ProviderType;
  name: string;
  config: Record<string, string>;
}

/** `POST /api/integrations/{id}/test` — test stored credentials. */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
}

/** `POST /api/integrations/{id}/send-test` — send a self-addressed test message. */
export interface SendTestResult {
  success: boolean;
  code: string | null;
  message: string | null;
  retryable: boolean;
  durationMs: number;
  httpStatus: number | null;
}

/** A remote workflow from a provider (discovered or monitored). */
export interface ExternalWorkflow {
  id: string;
  name: string;
  status: string;
  monitoring: boolean;
  lastSyncedAt: string | null;
}

/** `GET /api/integrations/{id}/workflows` — discovered + monitored workflows. */
export interface MonitoredWorkflowsResponse {
  workflows: ExternalWorkflow[];
}

/** `POST /api/integrations/{id}/workflows/{workflowId}/monitor` — start/stop monitoring. */
export interface MonitorActionRequest {
  monitoring: boolean;
}

export interface MonitorActionResponse {
  success: boolean;
  status: "MONITORING_STARTED" | "MONITORING_STOPPED";
}

/** `POST /api/integrations/{id}/sync` — manual sync trigger. */
export interface SyncActionResponse {
  workflows: number;
  executions: number;
  events: number;
  duplicates: number;
  status: string;
}

/** `GET /api/integrations/{id}/sync` — sync status. */
export interface SyncStatusResponse {
  status: "NEW" | "HEALTHY" | "FAILED";
  lastSyncAt: string | null;
  lastSuccessfulSyncAt: string | null;
  error: string | null;
}

// --- webhooks (M5 slice 2) ---------------------------------------------------
//
// A workflow's public inbound webhook: `POST /api/webhooks/{workflowId}/{token}`
// lets an external system start a run. The token IS the authentication; only its
// hash is stored server-side. A management read returns MASKED state only — never
// the token. The full working URL is returned exactly once, at generate time
// (`WebhookSecret`), and can never be recovered afterwards.

/** Masked webhook state shown on the management surface. */
export interface WebhookConfig {
  enabled: boolean;
  /** Non-secret last-4 of the token, safe to display. */
  tokenHint: string | null;
  /** Display-only URL with the token masked (`…/webhooks/{id}/••••{hint}`). */
  urlMasked: string;
  createdAt: string;
}

/** `GET /api/workflows/{id}/webhook` — `webhook` is null when none is configured. */
export interface WebhookResponse {
  webhook: WebhookConfig | null;
}

/**
 * `POST /api/workflows/{id}/webhook` — the ONE-TIME response to generating or
 * rotating the token. `url` embeds the secret and is shown only here; the client
 * must warn the user to copy it now.
 */
export interface WebhookSecret {
  url: string;
  tokenHint: string;
  enabled: boolean;
}

/** `PATCH /api/workflows/{id}/webhook` — enable or disable the inbound webhook. */
export interface SetWebhookEnabledBody {
  enabled: boolean;
}

// --- observability (M5 slice 4) ----------------------------------------------
//
// Three read surfaces over data the server already owns: the org-wide log viewer
// (the SAME `execution_logs` the engine writes — not a second logging system),
// the append-only audit trail, and the caller's own notification inbox. None of
// them can carry a secret: log lines come from executor outcomes, audit summaries
// are composed from role names / provider types / masked hints, and notifications
// from run state.

/**
 * One line in the org-wide log viewer. Unlike {@link ExecutionLogEntry} (nested in
 * a single run) each row names its own run and workflow, because the viewer
 * interleaves lines from many runs.
 */
export interface LogEntry {
  id: string;
  executionId: string;
  workflowId: string;
  workflowName: string;
  nodeId: string | null;
  level: LogLevel;
  message: string;
  seq: number;
  createdAt: string;
}

/** `GET /api/logs` — always an object, never a bare array. */
export interface LogsResponse {
  logs: LogEntry[];
}

/**
 * The security-relevant actions the trail records. Mirrors the backend
 * `AuditAction` enum: the names are part of the persisted contract, so this union
 * must stay in step with it.
 */
export type AuditAction =
  | "MEMBER_INVITED"
  | "INVITATION_REVOKED"
  | "INVITATION_ACCEPTED"
  | "MEMBER_ROLE_CHANGED"
  | "MEMBER_REMOVED"
  | "ORGANIZATION_RENAMED"
  | "PASSWORD_CHANGED"
  | "INTEGRATION_CONNECTED"
  | "INTEGRATION_DISCONNECTED"
  | "WEBHOOK_GENERATED"
  | "WEBHOOK_ENABLED"
  | "WEBHOOK_DISABLED"
  | "WEBHOOK_DELETED"
  | "WEBHOOK_AUTH_FAILED";

/**
 * One audit entry, readable by admins only. `actorUserId`/`actorEmail` are null
 * for anonymous events — a failed inbound-webhook authentication has no actor by
 * definition. `actorEmail` is a snapshot taken at write time, so the trail stays
 * readable after a rename or a removal.
 */
export interface AuditLogEntry {
  id: string;
  actorUserId: string | null;
  actorEmail: string | null;
  action: AuditAction;
  targetType: string | null;
  targetId: string | null;
  summary: string | null;
  ip: string | null;
  createdAt: string;
}

/** `GET /api/audit-logs` — admin-gated server-side; `hasRole` here is UX only. */
export interface AuditLogsResponse {
  auditLogs: AuditLogEntry[];
}

/** Severity of an in-app notification. */
export type NotificationLevel = "INFO" | "WARN" | "ERROR";

/**
 * One notification addressed to the signed-in user in their current org. `link` is
 * always a relative in-app path, so rendering it as an anchor can never navigate
 * off-origin. `readAt` is null until it is read.
 */
export interface AppNotification {
  id: string;
  level: NotificationLevel;
  title: string;
  body: string | null;
  link: string | null;
  readAt: string | null;
  createdAt: string;
}

/**
 * `GET /api/notifications` and both mark-read routes return the refreshed inbox,
 * `unreadCount` included, so the badge never needs a second request.
 */
export interface NotificationsResponse {
  notifications: AppNotification[];
  unreadCount: number;
}

// --- templates (M5 slice 5) -------------------------------------------------

/**
 * A built-in template. Product content served from the backend catalogue, not
 * tenant data — `GET /api/templates` returns the same list for every org.
 * `icon` is a PascalCase lucide name resolved by `resolveIcon`.
 */
export interface WorkflowTemplateSummary {
  slug: string;
  name: string;
  description: string;
  category: string;
  icon: string;
  tags: string[];
  nodeCount: number;
  providers: string[];
}
/** `GET /api/templates/{slug}` — the summary plus the graph, for a preview. */
export interface WorkflowTemplateDetail
  extends WorkflowTemplateSummary {
  graph: WorkflowGraph;
}

export interface TemplatesResponse {
  templates: WorkflowTemplateSummary[];
}

/* ------------------------------------------------------------ reliability */

/** Anomaly type (mirrors backend AnomalyType). */
export type AnomalyType =
  | "VOLUME"
  | "LATENCY"
  | "OUTPUT"
  | "BEHAVIORAL";

/** Anomaly severity (mirrors backend AnomalySeverity). */
export type AnomalySeverity =
  | "LOW"
  | "MEDIUM"
  | "HIGH"
  | "CRITICAL";

/** Anomaly lifecycle status (mirrors backend AnomalyStatus). */
export type AnomalyStatus =
  | "OPEN"
  | "ACKNOWLEDGED"
  | "VERIFYING_RECOVERY"
  | "RESOLVED"
  | "FALSE_POSITIVE";

export interface BaselineSnapshot {
  baseline: string;
  threshold: string;
  sampleCount: number;
}

export interface RecoveryStatus {
  anomalyStatus: AnomalyStatus;
  verificationActive: boolean;
  healthyCount: number;
  observedCount: number;
  requiredCount: number;
  startedAt: string | null;
  baseline: BaselineSnapshot | null;
}

/** Row in the anomalies list. */
export interface AnomalySummary {
  id: string;
  workflowId: string;
  workflowName: string;
  nodeId: string | null;
  nodeType: string | null;
  type: AnomalyType;
  severity: AnomalySeverity;
  status: AnomalyStatus;
  metric: string | null;
  expectedValue: string;
  actualValue: string;
  deviation: number;
  confidence: number;
  affectedExecutions: number;
  detectedAt: string;
  updatedAt: string;
}

/** Full anomaly detail. */
export interface AnomalyDetail {
  id: string;
  organizationId: string;
  workflowId: string;
  workflowName: string;
  nodeId: string | null;
  nodeType: string | null;
  executionId: string | null;
  type: AnomalyType;
  severity: AnomalySeverity;
  status: AnomalyStatus;
  metric: string | null;
  expectedValue: string;
  actualValue: string;
  deviation: number;
  confidence: number;
  affectedExecutions: number;
  recoveryStartedAt?: string | null;
  recoveryHealthyCount?: number;
  recoveryObservedCount?: number;
  recoveryRequiredCount?: number;
  evidence: Record<string, unknown> | null;
  dedupKey: string;
  detectedAt: string;
  updatedAt: string;
  createdAt: string;
}

export interface AnomaliesResponse {
  anomalies: AnomalySummary[];
}

/** Single anomaly detail response (wrapped in anomaly property). */
export interface AnomalyDetailResponse {
  anomaly: AnomalyDetail;
}

/** Anomaly acknowledgment. */
export interface AcknowledgeRequest {
  note?: string;
}

export interface AcknowledgeResponse {
  anomaly: AnomalyDetail;
}

/** Anomaly resolution. */
export interface ResolveRequest {
  note?: string;
}

export interface ResolveResponse {
  anomaly: AnomalyDetail;
}

/** False positive. */
export interface FalsePositiveRequest {
  reason: string;
}

export interface FalsePositiveResponse {
  anomaly: AnomalyDetail;
}

/** Workflow health / reliability score. */
export interface WorkflowHealth {
  workflowId: string;
  workflowName: string;
  reliabilityScore: number;
  totalExecutions: number;
  succeededExecutions: number;
  failedExecutions: number;
  successRate: number;
  openAnomalies: number;
  criticalAnomalies: number;
  highAnomalies: number;
  mediumAnomalies: number;
  lowAnomalies: number;
  lastExecutionAt: string | null;
  scoreComputedAt: string;
}

export interface WorkflowHealthResponse {
  health: WorkflowHealth;
}

/** Time-series metrics for charts. */
export interface MetricSeries {
  metric: string;
  nodeId: string | null;
  points: MetricPoint[];
}

export interface MetricPoint {
  at: string;
  value: number;
  label: string | null;
}

export interface WorkflowMetrics {
  workflowId: string;
  latency: MetricSeries[];
  volume: MetricSeries[];
  successRate: MetricSeries[];
  anomalyFrequency: MetricSeries[];
  reliabilityScoreHistory: MetricSeries[];
}

export interface WorkflowMetricsResponse {
  metrics: WorkflowMetrics;
}

/** AI investigation types. */
export interface LikelyCause {
  cause: string;
  category: string;
  confidence: number;
  uncertainty: string;
}

export interface EvidenceItem {
  type: string;
  description: string;
  source: string;
  inference: string;
}

export interface RecommendedAction {
  action: string;
  rationale: string;
  risk: string;
  effort: string;
}

export interface AIInvestigationResult {
  summary: string | null;
  likelyCauses: LikelyCause[];
  evidence: EvidenceItem[];
  impact: string | null;
  recommendedActions: RecommendedAction[];
  confidence: number;
  configured: boolean;
  model: string | null;
  generatedAt: string;
}

export interface InvestigateRequest {
  context?: Record<string, unknown>;
}

export interface InvestigateResponse {
  result: AIInvestigationResult;
}

/* ------------------------------------------------------------------ billing */

export type PlanId = "FREE" | "PRIVATE_BETA" | "PRO" | "BUSINESS";
export type BillingInterval = "MONTHLY" | "YEARLY";
export type SupportedCurrency =
  | "INR"
  | "USD"
  | "GBP"
  | "EUR"
  | "CAD"
  | "AUD"
  | "SGD"
  | "JPY";

export type SubscriptionStatus =
  | "ACTIVE"
  | "PAST_DUE"
  | "CANCELED"
  | "INCOMPLETE";

export interface SubscriptionRecord {
  id: string;
  workspaceId: string;
  plan: PlanId;
  status: SubscriptionStatus;
  currency: SupportedCurrency;
  billingCountry: string;
  billingInterval: BillingInterval;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  provider?: string;
  cancelAtPeriodEnd?: boolean;
  foundingOfferEligible?: boolean;
  foundingOfferApplied?: boolean;
  providerCustomerId?: string | null;
  providerSubscriptionId?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LocalizedPlanPrice {
  amountMinor: number;
  formatted: string;
  formattedStrikethrough?: string;
  isBetaZero: boolean;
  interval: BillingInterval;
  currency: SupportedCurrency;
  currencySymbol: string;
}

export interface LocalizedPlanView {
  id: PlanId;
  name: string;
  tagline: string;
  betaBadge?: string;
  isCustom: boolean;
  isCurrent: boolean;
  features: string[];
  limits: {
    workflows: number;
    executionsPerMonth: number;
    aiGenerationsPerMonth: number;
    teamMembers: number;
    featureFlags: Record<string, boolean>;
  };
  pricing?: LocalizedPlanPrice;
}

export interface WorkspaceUsageMetric {
  used: number;
  limit: number;
  percentage?: number;
}

export interface WorkspaceUsageData {
  plan: PlanId;
  workflows: WorkspaceUsageMetric;
  executions: WorkspaceUsageMetric;
  aiGenerations: WorkspaceUsageMetric;
  teamMembers: WorkspaceUsageMetric;
}

export interface BillingStateResponse {
  isPrivateBeta: boolean;
  isBillingEnabled: boolean;
  betaNotice: string;
  billingCountry: string;
  billingCurrency: SupportedCurrency;
  currencySymbol: string;
  displayCurrency: SupportedCurrency;
  providerSupported: boolean;
  activePlan: PlanId;
  subscription: SubscriptionRecord | null;
  usage: {
    workflows: { used: number; limit: number };
    executions: { used: number; limit: number };
    aiGenerations: { used: number; limit: number };
    teamMembers: { used: number; limit: number };
  };
  availableCurrencies: readonly SupportedCurrency[];
}

export interface BillingPlansResponse {
  plans: LocalizedPlanView[];
  interval: BillingInterval;
  displayCurrency: SupportedCurrency;
  billingCurrency: SupportedCurrency;
  billingCountry: string;
  currencySymbol: string;
  isDisplayCurrencyDifferent: boolean;
  displayCurrencyNotice: string | null;
  isPrivateBeta: boolean;
  isBillingEnabled: boolean;
  betaNotice: string;
}

export interface BillingCurrencyResponse {
  country: string;
  currency: SupportedCurrency;
  symbol: string;
  detectionSource: string;
  supportedCurrencies: readonly SupportedCurrency[];
}

export interface SubscriptionSummaryResponse {
  organizationId: string;
  plan: string;
  billingStatus: string;
  provider: string;
  subscriptionStatus: string;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  isPrivateBeta: boolean;
  betaEndsAt?: string | null;
  cancelAtPeriodEnd: boolean;
  renewalActive: boolean;
  foundingOfferEligible: boolean;
  foundingOfferApplied: boolean;
  foundingOfferEligibleUntil?: string | null;
  usage: {
    workflows: number;
    executions: number;
    aiGenerations: number;
    aiInvestigationsMonthly: number;
    aiInvestigationsTrial: number;
    seats: number;
    workspaces: number;
  };
  planLimits: {
    activeWorkflows: number;
    executionsPerMonth: number;
    aiGenerations: number;
    aiInvestigationsMonthly: number;
    aiInvestigationsLifetimeTrial: number;
    seats: number;
    workspaces: number;
    historyRetentionDays: number;
    workflowVersionsKept: number;
    allowedNotificationChannels: string[];
    anomalyDetectionMode: string;
  };
  remainingUsage: {
    workflows: number;
    executions: number;
    aiGenerations: number;
    aiInvestigations: number;
    seats: number;
    workspaces: number;
  };
  availablePlanTransitions: string[];
  billingEnabled: boolean;
}

export interface BillingInvoice {
  id: string;
  date?: string | null;
  amountPaid: number;
  currency: string;
  status: string;
  hostedInvoiceUrl?: string | null;
  pdfUrl?: string | null;
}

export interface BillingStatusResponse {
  subscription: SubscriptionSummaryResponse;
  entitlements: Record<string, any>;
  provider: string;
  isBillingEnabled: boolean;
  isPrivateBeta: boolean;
  foundingOfferEligible: boolean;
  foundingOfferApplied: boolean;
  dunningActive: boolean;
  pastDueSince?: string | null;
}

export interface FoundingOfferResponse {
  code: string;
  discountPercentage: number;
  startsAt: string;
  endsAt: string;
  active: boolean;
  message: string;
}

export interface CheckoutResponse {
  sessionId: string;
  checkoutUrl: string;
  provider: string;
  status: string;
  message?: string;
}

export interface PortalResponse {
  portalUrl: string;
}

export interface ChangePlanResponse {
  plan: string;
  status: string;
  interval: string;
  success: boolean;
  message: string;
}

export interface CancelSubscriptionResponse {
  status: string;
  cancelAtPeriodEnd: boolean;
  message: string;
}

export interface ResumeSubscriptionResponse {
  status: string;
  cancelAtPeriodEnd: boolean;
  message: string;
}