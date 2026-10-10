/**
 * Centralized TanStack Query key factory for Vantis.
 *
 * Guarantees consistent cache keys across pages, components, and command palettes,
 * avoiding duplicate network requests and ensuring reliable invalidation on mutations.
 */

export const queryKeys = {
  workflows: {
    all: ["workflows"] as const,
    list: (params?: Record<string, unknown>) => ["workflows", params ?? {}] as const,
    detail: (id: string) => ["workflows", "detail", id] as const,
    stats: (range?: string) => ["execution-stats", range ?? "7d"] as const,
  },
  executions: {
    all: ["executions"] as const,
    list: (params?: Record<string, unknown>) => ["executions", params ?? {}] as const,
    detail: (id: string) => ["executions", "detail", id] as const,
    external: (id: string) => ["executions", "external", id] as const,
    events: ["execution-events"] as const,
  },
  templates: {
    all: ["templates"] as const,
    detail: (slug: string) => ["templates", slug] as const,
  },
  anomalies: {
    all: ["anomalies"] as const,
    list: (status?: string) => ["reliability", "anomalies", status ?? "all"] as const,
    detail: (id: string) => ["reliability", "anomalies", "detail", id] as const,
    recovery: (id: string) => ["reliability", "anomalies", "recovery", id] as const,
  },
  agents: {
    all: ["ai-agents"] as const,
    detail: (id: string) => ["ai-agents", id] as const,
  },
  integrations: {
    all: ["integrations"] as const,
    providers: ["providers"] as const,
  },
  notifications: {
    all: ["notifications"] as const,
  },
  members: {
    all: ["members"] as const,
  },
  auditLogs: {
    all: ["audit-logs"] as const,
  },
  organization: {
    current: ["organization", "current"] as const,
    all: ["organizations"] as const,
  },
  billing: {
    state: (workspaceId?: string, currency?: string) =>
      ["billing", "state", workspaceId ?? "current", currency ?? "default"] as const,
    plans: (currency?: string, interval?: string) =>
      ["billing", "plans", currency ?? "default", interval ?? "MONTHLY"] as const,
    usage: (workspaceId?: string) =>
      ["billing", "usage", workspaceId ?? "current"] as const,
    currency: (country?: string) =>
      ["billing", "currency", country ?? "default"] as const,
    subscription: (workspaceId?: string) =>
      ["billing", "subscription", workspaceId ?? "current"] as const,
    invoices: (workspaceId?: string) =>
      ["billing", "invoices", workspaceId ?? "current"] as const,
    status: (workspaceId?: string) =>
      ["billing", "status", workspaceId ?? "current"] as const,
  },
} as const;
