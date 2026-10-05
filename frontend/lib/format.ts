import type { BadgeProps } from "@/components/ui/badge";
import type { ExecutionStatus, NodeRunStatus } from "@/types";

type BadgeVariant = NonNullable<BadgeProps["variant"]>;

/** Badge tint for a run status. */
export function executionStatusVariant(status: ExecutionStatus): BadgeVariant {
  switch (status) {
    case "SUCCEEDED":
      return "success";
    case "FAILED":
      return "destructive";
    case "WAITING":
      return "warning";
    case "RUNNING":
      return "default";
    case "QUEUED":
      return "secondary";
    case "CANCELED":
      return "outline";
  }
}

/** Badge tint for a single node's run status. */
export function nodeStatusVariant(status: NodeRunStatus): BadgeVariant {
  switch (status) {
    case "SUCCEEDED":
      return "success";
    case "FAILED":
      return "destructive";
    case "WAITING":
      return "warning";
    case "RUNNING":
      return "default";
    case "PENDING":
      return "secondary";
    case "SKIPPED":
      return "outline";
  }
}

/**
 * True while a run can still advance on its own — the worker is (or will be)
 * emitting frames. `WAITING` is excluded: it changes only via a human decision,
 * so there is nothing to poll for.
 */
export function isLiveStatus(status: ExecutionStatus): boolean {
  return status === "QUEUED" || status === "RUNNING";
}

/** Compact human duration: "820ms", "3.4s", "2m 5s", "1h 2m". */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(seconds < 10 ? 1 : 0)}s`;
  const minutes = Math.floor(seconds / 60);
  const remSeconds = Math.round(seconds % 60);
  if (minutes < 60) return `${minutes}m ${remSeconds}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

/** "just now", "5 minutes ago", "3 hours ago", then an absolute date past a week. */
export function formatRelativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "—";
  const diffSec = Math.round((then - Date.now()) / 1000);
  if (Math.abs(diffSec) < 45) return "just now";
  const diffMin = Math.round(diffSec / 60);
  if (Math.abs(diffMin) < 60) return RELATIVE.format(diffMin, "minute");
  const diffHr = Math.round(diffMin / 60);
  if (Math.abs(diffHr) < 24) return RELATIVE.format(diffHr, "hour");
  const diffDay = Math.round(diffHr / 24);
  if (Math.abs(diffDay) < 7) return RELATIVE.format(diffDay, "day");
  return formatDateTime(iso);
}

/** Absolute local date-time, e.g. "Aug 26, 2026, 3:04 PM". */
export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** Short bucket-axis label for the analytics chart. Hourly for 24h, else a date. */
export function formatBucketLabel(iso: string, hourly: boolean): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return hourly
    ? d.toLocaleTimeString(undefined, { hour: "numeric" })
    : d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/** Formats a trigger type code into human title format (e.g. "WEBHOOK_EVENT" -> "Webhook Event"). */
export function formatTrigger(value?: string | null): string {
  if (!value) return "—";
  return value
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

export {
  getStatusConfig,
  getHealthTone,
  getHealthLabel,
  getHealthStroke,
  type StatusTone,
  type StatusConfig,
} from "./status";

