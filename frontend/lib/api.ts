import axios, {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";

import { useAuthStore } from "@/lib/auth-store";
import type {
  AcceptInvitationBody,
  AgentRunResult,
  AiAgentBody,
  AiAgentDetail,
  AiAgentsResponse,
  ApiError,
  AuditAction,
  AuditLogsResponse,
  AuthResponse,
  ChangeMemberRoleBody,
  ChangePasswordBody,
  ConnectIntegrationBody,
  ConnectProviderBody,
  ConnectionTestResult,
  CreateOrganizationResponse,
  CurrentOrganizationResponse,
  CredentialField,
  ErrorCode,
  ExecutionDetail,
  ExecutionEvent,
  ExecutionEventsResponse,
  ExecutionStats,
  ExecutionStatus,
  ExecutionsResponse,
  ExternalWorkflow,
  FieldError,
  GenerateWorkflowResult,
  Integration,
  IntegrationsResponse,
  InvitationSecret,
  LogLevel,
  LogsResponse,
  MonitorActionResponse,
  MonitoredWorkflowsResponse,
  NotificationsResponse,
  InvitationsResponse,
  InviteMemberBody,
  OrganizationMembersResponse,
  OrganizationsResponse,
  ProvidersResponse,
  ProviderInfo,
  ProviderCapabilities,
  ProviderType,
  PublishResult,
  Role,
  SessionResponse,
  SetWebhookEnabledBody,
  StatsRange,
  SyncActionResponse,
  SyncStatusResponse,
  TemplatesResponse,
  ValidationResult,
  WebhookResponse,
  WebhookSecret,
  WorkflowDetail,
  WorkflowGraph,
  WorkflowStatus,
  WorkflowsResponse,
  WorkflowTemplateDetail,
  WorkflowVersion,
  WorkflowVersionsResponse,
  NodeTypesResponse,

  // Reliability
  AnomalyDetail,
  AnomalyDetailResponse,
  AnomaliesResponse,
  AnomalySeverity,
  AnomalyStatus,
  AnomalySummary,
  AnomalyType,
  AcknowledgeRequest,
  AcknowledgeResponse,
  ResolveRequest,
  ResolveResponse,
  FalsePositiveRequest,
  FalsePositiveResponse,
  RecoveryStatus,
  WorkflowHealth,
  WorkflowHealthResponse,
  WorkflowMetrics,
  WorkflowMetricsResponse,
  AIInvestigationResult,
  InvestigateRequest,
  InvestigateResponse,
  SendTestResult,

  // Billing
  BillingCurrencyResponse,
  BillingInterval,
  BillingPlansResponse,
  BillingStateResponse,
  PlanId,
  SubscriptionRecord,
  WorkspaceUsageData,
  PlanLimitDetail,
  SubscriptionSummaryResponse,
  BillingInvoice,
  BillingStatusResponse,
  FoundingOfferResponse,
  CheckoutResponse,
  PortalResponse,
  ChangePlanResponse,
  CancelSubscriptionResponse,
  ResumeSubscriptionResponse,
} from "@/types";

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** Endpoints that must never trigger a refresh-and-replay (they *are* the auth flow). */
const NO_REFRESH_PATHS = [
  "/api/auth/refresh",
  "/api/auth/login",
  "/api/auth/logout",
  "/api/auth/register",
  // Sent from a logged-out browser, so a 401 on these must never be treated as a
  // stale-token refresh trigger.
  "/api/auth/forgot-password",
  "/api/auth/reset-password",
];

/** 401 codes that are terminal: clear the store and go to /login, never retry. */
const TERMINAL_CODES: ReadonlySet<ErrorCode> = new Set<ErrorCode>([
  "REFRESH_TOKEN_INVALID",
  "AUTHENTICATION_REQUIRED",
  "TOKEN_INVALID",
]);

interface RetryableConfig extends InternalAxiosRequestConfig {
  /** Set once a request has already been replayed after a refresh. */
  _retried?: boolean;
}

/**
 * The application client. Every request carries credentials so the browser
 * attaches the HttpOnly `flowops_refresh` cookie on the two endpoints scoped to
 * `/api/auth`, and `Content-Type: application/json` so cookie-reading endpoints
 * always trigger a CORS preflight (the contract's CSRF defense).
 */
export const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
});

/**
 * A second, interceptor-free instance used exclusively for the refresh call, so
 * a 401 from `/api/auth/refresh` can never re-enter the refresh interceptor.
 */
const refreshClient = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
});

/* ------------------------------------------------------------ error access */

/**
 * Narrow an unknown thrown value to the contract's error envelope.
 * Returns `null` for network failures, CORS errors and cancellations, which
 * have no response body at all.
 */
export function getApiError(error: unknown): ApiError["error"] | null {
  if (!axios.isAxiosError(error)) return null;

  const data = (error as AxiosError<ApiError>).response?.data;

  if (!data || typeof data !== "object") return null;

  const envelope = (data as ApiError).error;

  if (!envelope || typeof envelope.code !== "string") return null;

  return envelope;
}

/** The stable `error.code`, or `null` when the failure had no envelope. */
export function getErrorCode(error: unknown): ErrorCode | null {
  return getApiError(error)?.code ?? null;
}

/**
 * Machine-readable details for PLAN_LIMIT_EXCEEDED errors.
 * Never requires clients to parse human-facing message strings.
 */
export function getPlanLimitDetail(error: unknown): PlanLimitDetail | null {
  const envelope = getApiError(error);
  if (envelope?.code === "PLAN_LIMIT_EXCEEDED" && envelope.details) {
    return envelope.details;
  }
  return null;
}

/**
 * A human-readable message safe to show in a toast. Falls back to a generic
 * line for transport-level failures, which carry no server message.
 */
export function getErrorMessage(
  error: unknown,
  fallback = "Something went wrong. Please try again.",
): string {
  const envelope = getApiError(error);

  if (envelope?.message) return envelope.message;

  if (axios.isAxiosError(error) && !error.response) {
    return "Cannot reach the Vantis API. Check that the server is running.";
  }

  if (error instanceof Error && error.message) {
    return error.message;
  }

  if (
    typeof error === "object" &&
    error !== null &&
    "message" in error &&
    typeof (error as any).message === "string" &&
    (error as any).message.trim().length > 0
  ) {
    return (error as any).message;
  }

  return fallback;
}

/**
 * Field-level messages from a `400 VALIDATION_ERROR`, keyed by the camelCase
 * JSON field name. Empty for every other code, so callers can render a
 * form-level message instead.
 */
export function getFieldErrors(
  error: unknown,
): Record<string, string> {
  const envelope = getApiError(error);

  if (
    envelope?.code !== "VALIDATION_ERROR" ||
    !envelope.fieldErrors
  ) {
    return {};
  }

  const out: Record<string, string> = {};

  for (const fe of envelope.fieldErrors as FieldError[]) {
    // First message per field wins; the backend may send several and order is
    // not guaranteed.
    if (!(fe.field in out)) {
      out[fe.field] = fe.message;
    }
  }

  return out;
}

/* --------------------------------------------------------------- redirects */

/**
 * Validates that a redirect target is a relative in-app path (no open redirect).
 * Allows paths starting with `/` but not `//` (protocol-relative) or absolute URLs.
 */
function isSafeRedirectPath(path: string): boolean {
  if (!path.startsWith("/")) return false;
  if (path.startsWith("//")) return false; // protocol-relative
  return true;
}

function redirectToLogin(next?: string) {
  if (typeof window === "undefined") return;

  if (window.location.pathname === "/login") return;

  // A hard navigation is intentional: it guarantees no in-memory tenant data
  // from the dead session survives into the login screen.
  const currentPath = window.location.pathname + window.location.search;
  const safeNext = next && isSafeRedirectPath(next) ? next : currentPath;
  const encodedNext = encodeURIComponent(safeNext);
  window.location.replace(`/login?next=${encodedNext}`);
}

/* ---------------------------------------------------- single-flight refresh */

let refreshPromise: Promise<AuthResponse> | null = null;

/**
 * Rotate the refresh cookie and mint a new access token.
 *
 * Refresh-token reuse detection is destructive — presenting an already-rotated
 * token revokes every session the user has — so concurrent callers must share
 * one in-flight request rather than each firing their own. The promise is
 * cleared in `finally` so the next 401 starts a fresh attempt.
 */
export function refreshSession(): Promise<AuthResponse> {
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post<AuthResponse>("/api/auth/refresh", {})
      .then((res) => {
        useAuthStore.getState().setAuth(res.data);
        return res.data;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }

  return refreshPromise;
}

/**
 * App-boot session recovery. The access token lives in memory only, so a reload
 * starts with nothing; one refresh call restores the token *and* a fresh
 * user/org/membership snapshot in a single round trip.
 *
 * A 401 here is the normal cold start for a logged-out visitor — it resolves
 * `false` rather than throwing, and must not raise an error toast.
 */
export async function bootstrapSession(): Promise<boolean> {
  try {
    await refreshSession();
    return true;
  } catch {
    useAuthStore.getState().clearAuth();
    return false;
  }
}

/* ------------------------------------------------------------ interceptors */

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;

  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }

  return config;
});

api.interceptors.response.use(
  (response: AxiosResponse) => response,

  async (error: AxiosError<ApiError>) => {
    const config =
      error.config as RetryableConfig | undefined;

    const status = error.response?.status;

    if (!config || status !== 401) {
      return Promise.reject(error);
    }

    const url = config.url ?? "";

    if (
      NO_REFRESH_PATHS.some((p) =>
        url.startsWith(p),
      )
    ) {
      return Promise.reject(error);
    }

    const code = getErrorCode(error);

    if (
      code &&
      TERMINAL_CODES.has(code)
    ) {
      useAuthStore.getState().clearAuth();
      redirectToLogin();
      return Promise.reject(error);
    }

    // `TOKEN_EXPIRED` is the designed refresh trigger. A 401 with no readable
    // envelope is treated the same way — one refresh attempt, then terminal.
    if (
      code !== null &&
      code !== "TOKEN_EXPIRED"
    ) {
      return Promise.reject(error);
    }

    if (config._retried) {
      useAuthStore.getState().clearAuth();
      redirectToLogin();
      return Promise.reject(error);
    }

    config._retried = true;

    try {
      await refreshSession();

      // Replaying through `api.request` re-runs the request interceptor, which
      // overwrites the stale Authorization header with the freshly minted token.
      return await api.request(config);
    } catch (refreshError) {
      useAuthStore.getState().clearAuth();
      redirectToLogin();
      return Promise.reject(refreshError);
    }
  },
);

/* --------------------------------------------------------------- endpoints */

export interface RegisterPayload {
  email: string;
  password: string;
  fullName: string;
  organizationName?: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

/** `POST /api/auth/register` → 201 `AuthResponse` + refresh cookie. */
export async function register(
  payload: RegisterPayload,
): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>(
    "/api/auth/register",
    payload,
  );

  useAuthStore.getState().setAuth(data);

  return data;
}

/** `POST /api/auth/login` → 200 `AuthResponse` + refresh cookie. */
export async function login(
  payload: LoginPayload,
): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>(
    "/api/auth/login",
    payload,
  );

  useAuthStore.getState().setAuth(data);

  return data;
}

/**
 * `POST /api/auth/logout` → 204. Idempotent and never fails, so the local store
 * is cleared unconditionally: an already-issued access token stays valid
 * server-side for up to 15 minutes and must not linger in memory.
 */
export async function logout(): Promise<void> {
  try {
    await api.post("/api/auth/logout", {});
  } finally {
    useAuthStore.getState().clearAuth();
  }
}

/** `POST /api/auth/forgot-password` → 204. Always returns generic success. */
export async function forgotPassword(payload: { email: string }): Promise<void> {
  await api.post("/api/auth/forgot-password", payload);
}

/** `POST /api/auth/reset-password` → 204. Validates token and sets new password. */
export async function resetPassword(payload: { token: string; newPassword: string }): Promise<void> {
  await api.post("/api/auth/reset-password", payload);
}

/** `GET /api/organizations` → every org the authenticated user belongs to. */
export async function fetchOrganizations(): Promise<OrganizationsResponse> {
  const { data } =
    await api.get<OrganizationsResponse>(
      "/api/organizations",
    );

  return data;
}

/** `GET /api/organizations/current` → the org named by the token's `orgId`. */
export async function fetchCurrentOrganization(): Promise<CurrentOrganizationResponse> {
  const { data } =
    await api.get<CurrentOrganizationResponse>(
      "/api/organizations/current",
    );

  return data;
}

/** `PATCH /api/organizations/current` → rename. Requires ADMIN or OWNER. */
export async function renameCurrentOrganization(
  name: string,
): Promise<CurrentOrganizationResponse> {
  const { data } =
    await api.patch<CurrentOrganizationResponse>(
      "/api/organizations/current",
      { name },
    );

  return data;
}

/** `POST /api/organizations` → creates a new organization. */
export async function createOrganization(payload: {
  organizationName: string;
}): Promise<CreateOrganizationResponse> {
  const { data } =
    await api.post<CreateOrganizationResponse>(
      "/api/organizations",
      payload,
    );

  return data;
}

/**
 * `POST /api/organizations/{organizationId}/switch` → 200 `AuthResponse` with a
 * new access token. No body, no `Set-Cookie`; the session row now carries the
 * new org so a later refresh returns it.
 */
export async function switchOrganization(
  organizationId: string,
): Promise<AuthResponse> {
  const { data } =
    await api.post<AuthResponse>(
      `/api/organizations/${organizationId}/switch`,
    );

  useAuthStore.getState().setAuth(data);

  return data;
}

/** `GET /api/organizations/current/members` → readable by any role. */
export async function fetchCurrentOrganizationMembers(): Promise<OrganizationMembersResponse> {
  const { data } =
    await api.get<OrganizationMembersResponse>(
      "/api/organizations/current/members",
    );

  return data;
}

/**
 * `PATCH /api/organizations/current/members/{userId}` → change a member's role.
 * Requires ADMIN or OWNER.
 */
export async function changeMemberRole(
  userId: string,
  role: Role,
): Promise<OrganizationMembersResponse> {
  const body: ChangeMemberRoleBody = { role };

  const { data } =
    await api.patch<OrganizationMembersResponse>(
      `/api/organizations/current/members/${userId}`,
      body,
    );

  return data;
}

/**
 * `DELETE /api/organizations/current/members/{userId}` → remove a member.
 * Requires ADMIN or OWNER.
 */
export async function removeMember(
  userId: string,
): Promise<OrganizationMembersResponse> {
  const { data } =
    await api.delete<OrganizationMembersResponse>(
      `/api/organizations/current/members/${userId}`,
    );

  return data;
}

/** `GET /api/organizations/current/invitations` → ADMIN/OWNER view of all invites. */
export async function fetchInvitations(): Promise<InvitationsResponse> {
  const { data } =
    await api.get<InvitationsResponse>(
      "/api/organizations/current/invitations",
    );

  return data;
}

/**
 * `POST /api/organizations/current/invitations` → 201.
 */
export async function inviteMember(
  body: InviteMemberBody,
): Promise<InvitationSecret> {
  const { data } =
    await api.post<InvitationSecret>(
      "/api/organizations/current/invitations",
      body,
    );

  return data;
}

/** `DELETE /api/organizations/current/invitations/{id}` → 204. */
export async function revokeInvitation(
  invitationId: string,
): Promise<void> {
  await api.delete(
    `/api/organizations/current/invitations/${invitationId}`,
  );
}

/**
 * `POST /api/invitations/accept` → join the invitation's org.
 */
export async function acceptInvitation(
  token: string,
): Promise<CreateOrganizationResponse> {
  const body: AcceptInvitationBody = { token };

  const { data } =
    await api.post<CreateOrganizationResponse>(
      "/api/invitations/accept",
      body,
    );

  return data;
}

/**
 * `POST /api/auth/change-password` → 204.
 */
export async function changePassword(
  body: ChangePasswordBody,
): Promise<void> {
  await api.post(
    "/api/auth/change-password",
    body,
  );
}

/* ---------------------------------------------------------------- workflows */

/** `GET /api/node-types` → the global node registry for the builder palette. */
export async function fetchNodeTypes(): Promise<NodeTypesResponse> {
  const { data } =
    await api.get<NodeTypesResponse>(
      "/api/node-types",
    );

  return data;
}

/** `GET /api/workflows` → org-scoped list with optional search / status filter. */
export async function fetchWorkflows(params?: {
  search?: string;
  status?: WorkflowStatus;
}): Promise<WorkflowsResponse> {
  const { data } =
    await api.get<WorkflowsResponse>(
      "/api/workflows",
      {
        params: {
          search: params?.search || undefined,
          status: params?.status || undefined,
        },
      },
    );

  return data;
}

/** `POST /api/workflows` → 201 with an empty draft graph. */
export async function createWorkflow(payload: {
  name: string;
  description?: string;
}): Promise<WorkflowDetail> {
  const { data } =
    await api.post<WorkflowDetail>(
      "/api/workflows",
      payload,
    );

  return data;
}

/** `GET /api/workflows/{id}` → workflow with its editable draft graph. */
export async function fetchWorkflow(
  id: string,
): Promise<WorkflowDetail> {
  const { data } =
    await api.get<WorkflowDetail>(
      `/api/workflows/${id}`,
    );

  return data;
}

/** `PATCH /api/workflows/{id}` → rename / re-describe. */
export async function updateWorkflow(
  id: string,
  payload: {
    name: string;
    description?: string;
  },
): Promise<WorkflowDetail> {
  const { data } =
    await api.patch<WorkflowDetail>(
      `/api/workflows/${id}`,
      payload,
    );

  return data;
}

/** `PUT /api/workflows/{id}/graph` → save the working graph verbatim. */
export async function saveWorkflowGraph(
  id: string,
  graph: WorkflowGraph,
): Promise<WorkflowDetail> {
  const { data } =
    await api.put<WorkflowDetail>(
      `/api/workflows/${id}/graph`,
      { graph },
    );

  return data;
}

/** `POST /api/workflows/{id}/validate` → validation result. */
export async function validateWorkflow(
  id: string,
): Promise<ValidationResult> {
  const { data } =
    await api.post<ValidationResult>(
      `/api/workflows/${id}/validate`,
    );

  return data;
}

/** `POST /api/workflows/{id}/publish` → always 200. */
export async function publishWorkflow(
  id: string,
  note?: string,
): Promise<PublishResult> {
  const { data } =
    await api.post<PublishResult>(
      `/api/workflows/${id}/publish`,
      { note: note ?? null },
    );

  return data;
}

/** `GET /api/workflows/{id}/versions`. */
export async function fetchWorkflowVersions(
  id: string,
): Promise<WorkflowVersionsResponse> {
  const { data } =
    await api.get<WorkflowVersionsResponse>(
      `/api/workflows/${id}/versions`,
    );

  return data;
}

/** `GET /api/workflows/{id}/versions/{n}`. */
export async function fetchWorkflowVersion(
  id: string,
  versionNumber: number,
): Promise<WorkflowVersion> {
  const { data } =
    await api.get<WorkflowVersion>(
      `/api/workflows/${id}/versions/${versionNumber}`,
    );

  return data;
}

/** `DELETE /api/workflows/{id}` → 204. */
export async function deleteWorkflow(
  id: string,
): Promise<void> {
  await api.delete(`/api/workflows/${id}`);
}

/* --------------------------------------------------------------- executions */

/**
 * `POST /api/workflows/{id}/run` → 201 `ExecutionDetail`.
 */
export async function runWorkflow(
  workflowId: string,
  input?: unknown,
): Promise<ExecutionDetail> {
  const { data } =
    await api.post<ExecutionDetail>(
      `/api/workflows/${workflowId}/run`,
      input === undefined
        ? {}
        : { input },
    );

  return data;
}

/**
 * `GET /api/executions` → org-scoped native Vantis runs.
 */
export async function fetchExecutions(params?: {
  workflowId?: string;
  status?: ExecutionStatus;
  limit?: number;
}): Promise<ExecutionsResponse> {
  const { data } =
    await api.get<ExecutionsResponse>(
      "/api/executions",
      {
        params: {
          workflowId:
            params?.workflowId || undefined,
          status:
            params?.status || undefined,
          limit:
            params?.limit || undefined,
        },
      },
    );

  return data;
}

/**
 * `GET /api/executions/{id}` → native Vantis run detail.
 */
export async function fetchExecution(
  id: string,
): Promise<ExecutionDetail> {
  const { data } =
    await api.get<ExecutionDetail>(
      `/api/executions/${id}`,
    );

  return data;
}

/**
 * `GET /api/v1/execution-events` → external/provider execution events.
 *
 * This is intentionally separate from `/api/executions`.
 * Native Vantis executions are represented by `ExecutionDetail`, while
 * monitored provider executions such as n8n are represented by
 * `ExecutionEvent` records.
 */
export async function fetchExecutionEvents(params?: {
  workflowId?: string;
  executionExternalId?: string;
  workflowExternalId?: string;
  limit?: number;
}): Promise<ExecutionEventsResponse> {
  const { data } =
    await api.get<ExecutionEventsResponse>(
      "/api/v1/execution-events",
      {
        params: {
          workflowId:
            params?.workflowId || undefined,
          executionExternalId:
            params?.executionExternalId || undefined,
          workflowExternalId:
            params?.workflowExternalId || undefined,
          limit:
            params?.limit || undefined,
        },
      },
    );

  return data;
}

/**
 * `POST /api/executions/{id}/nodes/{nodeId}/decision`.
 */
export async function decideApproval(
  executionId: string,
  nodeId: string,
  decision: {
    approved: boolean;
    note?: string;
  },
): Promise<ExecutionDetail> {
  const { data } =
    await api.post<ExecutionDetail>(
      `/api/executions/${executionId}/nodes/${nodeId}/decision`,
      {
        approved: decision.approved,
        note: decision.note ?? null,
      },
    );

  return data;
}

/**
 * `POST /api/executions/{id}/retry`.
 */
export async function retryExecution(
  id: string,
): Promise<ExecutionDetail> {
  const { data } =
    await api.post<ExecutionDetail>(
      `/api/executions/${id}/retry`,
    );

  return data;
}

/**
 * `POST /api/executions/{id}/cancel`.
 */
export async function cancelExecution(
  id: string,
): Promise<ExecutionDetail> {
  const { data } =
    await api.post<ExecutionDetail>(
      `/api/executions/${id}/cancel`,
    );

  return data;
}

/**
 * `GET /api/executions/stats?range=…`.
 */
export async function fetchExecutionStats(
  range: StatsRange,
): Promise<ExecutionStats> {
  const { data } =
    await api.get<ExecutionStats>(
      "/api/executions/stats",
      {
        params: { range },
      },
    );

  return data;
}

/* ---------------------------------------------------------------- ai agents */

/** `GET /api/ai/agents`. */
export async function fetchAiAgents(): Promise<AiAgentsResponse> {
  const { data } =
    await api.get<AiAgentsResponse>(
      "/api/ai/agents",
    );

  return data;
}

/** `POST /api/ai/agents`. */
export async function createAiAgent(
  body: AiAgentBody,
): Promise<AiAgentDetail> {
  const { data } =
    await api.post<AiAgentDetail>(
      "/api/ai/agents",
      body,
    );

  return data;
}

/** `GET /api/ai/agents/{id}`. */
export async function fetchAiAgent(
  id: string,
): Promise<AiAgentDetail> {
  const { data } =
    await api.get<AiAgentDetail>(
      `/api/ai/agents/${id}`,
    );

  return data;
}

/** `PATCH /api/ai/agents/{id}`. */
export async function updateAiAgent(
  id: string,
  body: AiAgentBody,
): Promise<AiAgentDetail> {
  const { data } =
    await api.patch<AiAgentDetail>(
      `/api/ai/agents/${id}`,
      body,
    );

  return data;
}

/** `DELETE /api/ai/agents/{id}` → 204. */
export async function deleteAiAgent(
  id: string,
): Promise<void> {
  await api.delete(`/api/ai/agents/${id}`);
}

/**
 * `POST /api/ai/agents/{id}/run`.
 */
export async function runAiAgent(
  id: string,
  input?: string,
): Promise<AgentRunResult> {
  const { data } =
    await api.post<AgentRunResult>(
      `/api/ai/agents/${id}/run`,
      {
        input: input ?? null,
      },
    );

  return data;
}

/**
 * `POST /api/ai/generate-workflow`.
 */
export async function generateWorkflow(
  prompt: string,
): Promise<GenerateWorkflowResult> {
  const { data } =
    await api.post<GenerateWorkflowResult>(
      "/api/ai/generate-workflow",
      { prompt },
    );

  return data;
}

/* ------------------------------------------------------------ integrations */

/**
 * `GET /api/integrations`.
 */
export async function fetchIntegrations(): Promise<IntegrationsResponse> {
  const { data } =
    await api.get<IntegrationsResponse>(
      "/api/integrations",
    );

  return data;
}

/** `GET /api/integrations/{id}`. */
export async function fetchIntegration(
  id: string,
): Promise<Integration> {
  const { data } =
    await api.get<Integration>(
      `/api/integrations/${id}`,
    );

  return data;
}

/**
 * `POST /api/integrations`.
 */
export async function connectIntegration(
  body: ConnectIntegrationBody,
): Promise<Integration> {
  const { data } =
    await api.post<Integration>(
      "/api/integrations",
      body,
    );

  return data;
}

/**
 * `DELETE /api/integrations/{id}`.
 */
export async function disconnectIntegration(
  id: string,
): Promise<void> {
  await api.delete(`/api/integrations/${id}`);
}

/* --- provider-agnostic integrations -------------------- */

/**
 * `GET /api/integrations/providers`.
 */
export async function fetchProviders(): Promise<ProvidersResponse> {
  const { data } =
    await api.get<ProvidersResponse>(
      "/api/integrations/providers",
    );

  return data;
}

/**
 * `POST /api/integrations` generic provider connection.
 */
export async function connectProvider(
  body: ConnectProviderBody,
): Promise<Integration> {
  const { data } =
    await api.post<Integration>(
      "/api/integrations/providers",
      body,
    );

  return data;
}

/**
 * `POST /api/integrations/{id}/test`.
 */
export async function testIntegration(
  id: string,
): Promise<ConnectionTestResult> {
  const { data } =
    await api.post<ConnectionTestResult>(
      `/api/integrations/${id}/test`,
    );

  return data;
}

/**
 * `POST /api/integrations/{id}/send-test`.
 */
export async function sendTestIntegration(
  id: string,
): Promise<SendTestResult> {
  const { data } =
    await api.post<SendTestResult>(
      `/api/integrations/${id}/send-test`,
    );

  return data;
}

/**
 * `GET /api/integrations/{id}/workflows`.
 */
export async function discoverIntegrationWorkflows(
  id: string,
): Promise<MonitoredWorkflowsResponse> {
  const { data } =
    await api.get<MonitoredWorkflowsResponse>(
      `/api/integrations/${id}/workflows`,
    );

  return data;
}

/**
 * `POST /api/integrations/{id}/workflows/{workflowId}/monitor`.
 */
export async function monitorProviderWorkflow(
  id: string,
  workflowId: string,
  monitoring: boolean,
): Promise<MonitorActionResponse> {
  const { data } =
    await api.post<MonitorActionResponse>(
      `/api/integrations/${id}/workflows/${workflowId}/monitor`,
      { monitoring },
    );

  return data;
}

/**
 * `POST /api/integrations/{id}/sync`.
 */
export async function syncIntegration(
  id: string,
): Promise<SyncActionResponse> {
  const { data } =
    await api.post<SyncActionResponse>(
      `/api/integrations/${id}/sync`,
    );

  return data;
}

/**
 * `GET /api/integrations/{id}/sync`.
 */
export async function fetchSyncStatus(
  id: string,
): Promise<SyncStatusResponse> {
  const { data } =
    await api.get<SyncStatusResponse>(
      `/api/integrations/${id}/sync`,
    );

  return data;
}

/* --- webhooks ------------------------------------------------------------- */

export async function fetchWorkflowWebhook(
  workflowId: string,
): Promise<WebhookResponse> {
  const { data } =
    await api.get<WebhookResponse>(
      `/api/workflows/${workflowId}/webhook`,
    );

  return data;
}

export async function generateWorkflowWebhook(
  workflowId: string,
): Promise<WebhookSecret> {
  const { data } =
    await api.post<WebhookSecret>(
      `/api/workflows/${workflowId}/webhook`,
    );

  return data;
}

export async function setWorkflowWebhookEnabled(
  workflowId: string,
  enabled: boolean,
): Promise<WebhookResponse> {
  const body: SetWebhookEnabledBody = {
    enabled,
  };

  const { data } =
    await api.patch<WebhookResponse>(
      `/api/workflows/${workflowId}/webhook`,
      body,
    );

  return data;
}

export async function deleteWorkflowWebhook(
  workflowId: string,
): Promise<void> {
  await api.delete(
    `/api/workflows/${workflowId}/webhook`,
  );
}

/* --- observability -------------------------------------------------------- */

export async function fetchLogs(params?: {
  workflowId?: string;
  level?: LogLevel;
  limit?: number;
}): Promise<LogsResponse> {
  const { data } =
    await api.get<LogsResponse>(
      "/api/logs",
      {
        params: {
          workflowId: params?.workflowId,
          level: params?.level,
          limit: params?.limit,
        },
      },
    );

  return data;
}

export async function fetchAuditLogs(params?: {
  action?: AuditAction;
  limit?: number;
}): Promise<AuditLogsResponse> {
  const { data } =
    await api.get<AuditLogsResponse>(
      "/api/audit-logs",
      {
        params: {
          action: params?.action,
          limit: params?.limit,
        },
      },
    );

  return data;
}

export async function fetchNotifications(params?: {
  unreadOnly?: boolean;
  limit?: number;
}): Promise<NotificationsResponse> {
  const { data } =
    await api.get<NotificationsResponse>(
      "/api/notifications",
      {
        params: {
          unreadOnly: params?.unreadOnly,
          limit: params?.limit,
        },
      },
    );

  return data;
}

export async function markNotificationRead(
  id: string,
): Promise<NotificationsResponse> {
  const { data } =
    await api.post<NotificationsResponse>(
      `/api/notifications/${id}/read`,
    );

  return data;
}

export async function markAllNotificationsRead(): Promise<NotificationsResponse> {
  const { data } =
    await api.post<NotificationsResponse>(
      "/api/notifications/read-all",
    );

  return data;
}

/* --- templates ------------------------------------------------------------ */

export async function fetchTemplates(): Promise<TemplatesResponse> {
  const { data } =
    await api.get<TemplatesResponse>(
      "/api/templates",
    );

  return data;
}

export async function fetchTemplate(
  slug: string,
): Promise<WorkflowTemplateDetail> {
  const { data } =
    await api.get<WorkflowTemplateDetail>(
      `/api/templates/${slug}`,
    );

  return data;
}

export async function useTemplate(
  slug: string,
  body?: { name?: string },
): Promise<WorkflowDetail> {
  const { data } =
    await api.post<WorkflowDetail>(
      `/api/templates/${slug}/use`,
      body ?? {},
    );

  return data;
}

/* ------------------------------------------------------------ reliability */

export async function fetchAnomalies(params?: {
  status?: AnomalyStatus;
  workflowId?: string;
}): Promise<AnomaliesResponse> {
  const { data } =
    await api.get<AnomaliesResponse>(
      "/api/reliability/anomalies",
      {
        params: {
          status:
            params?.status || undefined,
          workflowId:
            params?.workflowId || undefined,
        },
      },
    );

  return data;
}

export async function fetchAnomaly(
  id: string,
): Promise<AnomalyDetail> {
  const { data } =
    await api.get<AnomalyDetailResponse>(
      `/api/reliability/anomalies/${id}`,
    );

  return data.anomaly;
}

export async function acknowledgeAnomaly(
  id: string,
  note?: string,
): Promise<AcknowledgeResponse> {
  const { data } =
    await api.post<AcknowledgeResponse>(
      `/api/reliability/anomalies/${id}/acknowledge`,
      note ? { note } : {},
    );

  return data;
}

export async function resolveAnomaly(
  id: string,
  note?: string,
): Promise<ResolveResponse> {
  const { data } =
    await api.post<ResolveResponse>(
      `/api/reliability/anomalies/${id}/resolve`,
      note ? { note } : {},
    );

  return data;
}

export async function markAnomalyFalsePositive(
  id: string,
  reason: string,
): Promise<FalsePositiveResponse> {
  const { data } =
    await api.post<FalsePositiveResponse>(
      `/api/reliability/anomalies/${id}/false-positive`,
      { reason },
    );

  return data;
}

export async function investigateAnomaly(
  id: string,
): Promise<InvestigateResponse> {
  const { data } =
    await api.post<InvestigateResponse>(
      `/api/reliability/anomalies/${id}/investigate`,
      {},
    );

  return data;
}

export async function verifyRecovery(
  id: string,
  requiredCount?: number,
): Promise<AnomalyDetail> {
  const { data } =
    await api.post<{ anomaly: AnomalyDetail }>(
      `/api/reliability/anomalies/${id}/verify-recovery`,
      requiredCount ? { requiredCount } : {},
    );

  return data.anomaly;
}

export async function fetchRecoveryStatus(
  id: string,
): Promise<RecoveryStatus> {
  const { data } =
    await api.get<{ snapshot: RecoveryStatus }>(
      `/api/reliability/anomalies/${id}/recovery`,
    );

  return data.snapshot;
}

/* ------------------------------------------------------------------ billing */

export async function fetchBillingState(params?: {
  workspaceId?: string;
  currency?: string;
  userCountry?: string;
  workspaceCountry?: string;
}): Promise<BillingStateResponse> {
  const q = new URLSearchParams();
  if (params?.workspaceId) q.set("workspaceId", params.workspaceId);
  if (params?.currency) q.set("currency", params.currency);
  if (params?.userCountry) q.set("userCountry", params.userCountry);
  if (params?.workspaceCountry) q.set("workspaceCountry", params.workspaceCountry);

  const res = await fetch(`/api/billing?${q.toString()}`);
  if (!res.ok) throw new Error("Failed to fetch billing status");
  return res.json();
}

export async function fetchBillingPlans(params?: {
  workspaceId?: string;
  currency?: string;
  interval?: "MONTHLY" | "YEARLY";
}): Promise<BillingPlansResponse> {
  const q = new URLSearchParams();
  if (params?.workspaceId) q.set("workspaceId", params.workspaceId);
  if (params?.currency) q.set("currency", params.currency);
  if (params?.interval) q.set("interval", params.interval);

  const res = await fetch(`/api/billing/plans?${q.toString()}`);
  if (!res.ok) throw new Error("Failed to fetch billing plans");
  return res.json();
}

export async function fetchBillingUsage(workspaceId?: string): Promise<WorkspaceUsageData> {
  try {
    const { data } = await api.get<any>("/api/billing/usage");
    return {
      plan: data.plan,
      workflows: data.workflows ?? data.activeWorkflows ?? { used: 0, limit: 50 },
      executions: data.executions ?? { used: 0, limit: 10000 },
      aiGenerations: data.aiGenerations ?? { used: 0, limit: 100 },
      teamMembers: data.teamMembers ?? { used: 1, limit: 5 },
    };
  } catch {
    const q = new URLSearchParams();
    if (workspaceId) q.set("workspaceId", workspaceId);
    const res = await fetch(`/api/billing/usage?${q.toString()}`);
    if (!res.ok) throw new Error("Failed to fetch workspace billing usage");
    return res.json();
  }
}

export async function fetchBillingCurrency(country?: string): Promise<BillingCurrencyResponse> {
  const q = new URLSearchParams();
  if (country) q.set("country", country);

  const res = await fetch(`/api/billing/currency?${q.toString()}`);
  if (!res.ok) throw new Error("Failed to resolve billing currency");
  return res.json();
}

export async function createBillingCheckout(payload: {
  workspaceId?: string;
  plan: string;
  interval: string;
  currency?: string;
  successUrl?: string;
  cancelUrl?: string;
}): Promise<CheckoutResponse> {
  try {
    const { data } = await api.post<CheckoutResponse>("/api/billing/checkout", payload);
    return data;
  } catch (err) {
    const res = await fetch("/api/billing/checkout", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || "Checkout unavailable");
    }
    return {
      sessionId: data.sessionId,
      checkoutUrl: data.url || data.checkoutUrl,
      provider: data.provider || "noop",
      status: data.status,
      message: data.message,
    };
  }
}

export async function cancelBillingSubscription(
  subscriptionIdOrOptions?: string | { atPeriodEnd?: boolean },
): Promise<CancelSubscriptionResponse> {
  const atPeriodEnd = typeof subscriptionIdOrOptions === "object" ? subscriptionIdOrOptions.atPeriodEnd : true;
  try {
    const { data } = await api.post<CancelSubscriptionResponse>("/api/billing/cancel", { atPeriodEnd });
    return data;
  } catch (err) {
    const res = await fetch("/api/billing/cancel", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        subscriptionId: typeof subscriptionIdOrOptions === "string" ? subscriptionIdOrOptions : undefined,
        atPeriodEnd,
      }),
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.message || "Cancellation unavailable");
    }
    return {
      status: data.status || "CANCELED",
      cancelAtPeriodEnd: atPeriodEnd ?? true,
      message: data.message || "Subscription cancelled",
    };
  }
}

export async function fetchBillingSubscription(): Promise<SubscriptionSummaryResponse> {
  const { data } = await api.get<SubscriptionSummaryResponse>("/api/billing/subscription");
  return data;
}

export async function fetchBillingStatus(): Promise<BillingStatusResponse> {
  const { data } = await api.get<BillingStatusResponse>("/api/billing/status");
  return data;
}

export async function fetchBillingInvoices(): Promise<BillingInvoice[]> {
  try {
    const { data } = await api.get<BillingInvoice[]>("/api/billing/invoices");
    return data;
  } catch {
    return [];
  }
}

export async function createBillingPortalSession(returnUrl?: string): Promise<PortalResponse> {
  const { data } = await api.post<PortalResponse>("/api/billing/portal", { returnUrl });
  return data;
}

export async function changeBillingPlan(payload: {
  plan: string;
  interval?: string;
}): Promise<ChangePlanResponse> {
  const { data } = await api.post<ChangePlanResponse>("/api/billing/change-plan", payload);
  return data;
}

export async function resumeBillingSubscription(): Promise<ResumeSubscriptionResponse> {
  const { data } = await api.post<ResumeSubscriptionResponse>("/api/billing/resume", {});
  return data;
}

export async function claimFoundingOffer(): Promise<FoundingOfferResponse> {
  const { data } = await api.post<FoundingOfferResponse>("/api/billing/founding-offer", {});
  return data;
}