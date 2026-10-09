/**
 * Unified Status Tokens and System Configuration for Vantis
 *
 * Single source of truth for execution, health, workflow, node,
 * and reliability status signals across the platform.
 */

export type StatusTone =
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "active"
  | "neutral";

export interface StatusConfig {
  tone: StatusTone;
  label: string;
  dotClass: string;
  badgeClass: string;
  textClass: string;
  bgClass: string;
  borderClass: string;
}

/**
 * Normalizes any operational status string and returns the unified tokens.
 */
export function getStatusConfig(status?: string | null): StatusConfig {
  const normalized = (status ?? "UNKNOWN").trim().toUpperCase();

  switch (normalized) {
    case "SUCCEEDED":
    case "SUCCESS":
    case "HEALTHY":
    case "PUBLISHED":
    case "CONNECTED":
    case "RESOLVED":
    case "POSITIVE":
      return {
        tone: "success",
        label: "Succeeded",
        dotClass: "bg-emerald-500 dark:bg-emerald-400",
        badgeClass:
          "border-emerald-500/20 bg-emerald-500/10 text-emerald-700 dark:bg-emerald-500/[0.08] dark:text-emerald-300",
        textClass: "text-emerald-600 dark:text-emerald-400",
        bgClass: "bg-emerald-500/10 dark:bg-emerald-500/[0.08]",
        borderClass: "border-emerald-500/20",
      };

    case "RUNNING":
    case "LIVE":
    case "EXECUTING":
    case "VERIFYING":
    case "VERIFYING_RECOVERY":
      return {
        tone: "active",
        label: "Running",
        dotClass: "bg-emerald-500 dark:bg-emerald-400 animate-pulse",
        badgeClass:
          "border-emerald-500/25 bg-emerald-500/15 text-emerald-800 dark:bg-emerald-500/[0.10] dark:text-emerald-300",
        textClass: "text-emerald-700 dark:text-emerald-300",
        bgClass: "bg-emerald-500/15 dark:bg-emerald-500/[0.10]",
        borderClass: "border-emerald-500/25",
      };

    case "WAITING":
    case "APPROVAL":
    case "HUMAN_APPROVAL":
    case "PAUSED":
    case "DRAFT":
    case "DEGRADED":
    case "ACKNOWLEDGED":
      return {
        tone: "warning",
        label: "Waiting",
        dotClass: "bg-amber-500 dark:bg-amber-400",
        badgeClass:
          "border-amber-500/20 bg-amber-500/10 text-amber-800 dark:bg-amber-500/[0.08] dark:text-amber-300",
        textClass: "text-amber-700 dark:text-amber-400",
        bgClass: "bg-amber-500/10 dark:bg-amber-500/[0.08]",
        borderClass: "border-amber-500/20",
      };

    case "FAILED":
    case "FAILURE":
    case "ERROR":
    case "CRITICAL":
    case "DISCONNECTED":
    case "OPEN":
    case "DOWN":
      return {
        tone: "danger",
        label: "Failed",
        dotClass: "bg-red-500 dark:bg-red-400",
        badgeClass:
          "border-red-500/20 bg-red-500/10 text-red-700 dark:bg-red-500/[0.08] dark:text-red-300",
        textClass: "text-red-600 dark:text-red-400",
        bgClass: "bg-red-500/10 dark:bg-red-500/[0.08]",
        borderClass: "border-red-500/20",
      };

    case "QUEUED":
    case "PENDING":
    case "INVESTIGATING":
    case "INFO":
      return {
        tone: "info",
        label: "Queued",
        dotClass: "bg-blue-500 dark:bg-blue-400",
        badgeClass:
          "border-blue-500/20 bg-blue-500/10 text-blue-700 dark:bg-blue-500/[0.08] dark:text-blue-300",
        textClass: "text-blue-600 dark:text-blue-400",
        bgClass: "bg-blue-500/10 dark:bg-blue-500/[0.08]",
        borderClass: "border-blue-500/20",
      };

    case "CANCELED":
    case "CANCELLED":
    case "SKIPPED":
    case "ARCHIVED":
    case "FALSE_POSITIVE":
    case "NEUTRAL":
    default:
      return {
        tone: "neutral",
        label:
          status && status.length > 0
            ? status.charAt(0).toUpperCase() +
              status.slice(1).toLowerCase().replace(/_/g, " ")
            : "—",
        dotClass: "bg-muted-foreground/50 dark:bg-white/35",
        badgeClass:
          "border-border bg-muted/60 text-muted-foreground dark:border-white/[0.08] dark:bg-white/[0.03] dark:text-white/60",
        textClass: "text-muted-foreground dark:text-white/60",
        bgClass: "bg-muted/60 dark:bg-white/[0.03]",
        borderClass: "border-border dark:border-white/[0.08]",
      };
  }
}

/**
 * Tone mapper for health metrics / success rates.
 */
export function getHealthTone(
  rate: number | null | undefined,
): StatusTone {
  if (rate == null) return "neutral";
  if (rate >= 95) return "success";
  if (rate >= 80) return "warning";
  return "danger";
}

/**
 * Human-readable label mapper for health metrics / success rates.
 */
export function getHealthLabel(
  rate: number | null | undefined,
): string {
  if (rate == null) return "No data";
  if (rate >= 99) return "Excellent";
  if (rate >= 95) return "Healthy";
  if (rate >= 80) return "Needs attention";
  return "At risk";
}

/**
 * SVG stroke color for health metric progress rings.
 */
export function getHealthStroke(
  rate: number | null | undefined,
): string {
  if (rate == null) return "rgba(255,255,255,0.12)";
  if (rate >= 95) return "rgb(52 211 153)";
  if (rate >= 80) return "rgb(251 191 36)";
  return "rgb(248 113 113)";
}
