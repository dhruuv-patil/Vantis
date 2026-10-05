import { describe, expect, it } from "vitest";
import {
  executionStatusVariant,
  formatBucketLabel,
  formatDateTime,
  formatDuration,
  formatRelativeTime,
  formatTrigger,
  isLiveStatus,
  nodeStatusVariant,
} from "@/lib/format";
import { getHealthLabel, getHealthTone, getStatusConfig } from "@/lib/status";

describe("formatDuration", () => {
  it("handles null and undefined", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(undefined)).toBe("—");
  });

  it("formats sub-second durations", () => {
    expect(formatDuration(0)).toBe("0ms");
    expect(formatDuration(450)).toBe("450ms");
    expect(formatDuration(999)).toBe("999ms");
  });

  it("formats second durations", () => {
    expect(formatDuration(1200)).toBe("1.2s");
    expect(formatDuration(15000)).toBe("15s");
    expect(formatDuration(59000)).toBe("59s");
  });

  it("formats minute durations", () => {
    expect(formatDuration(65000)).toBe("1m 5s");
    expect(formatDuration(120000)).toBe("2m 0s");
  });

  it("formats hour durations", () => {
    expect(formatDuration(3660000)).toBe("1h 1m");
  });
});

describe("formatTrigger", () => {
  it("formats empty and null triggers", () => {
    expect(formatTrigger(null)).toBe("—");
    expect(formatTrigger(undefined)).toBe("—");
    expect(formatTrigger("")).toBe("—");
  });

  it("formats uppercase underscore codes to Title Case", () => {
    expect(formatTrigger("MANUAL")).toBe("Manual");
    expect(formatTrigger("WEBHOOK")).toBe("Webhook");
    expect(formatTrigger("SCHEDULE_CRON")).toBe("Schedule Cron");
    expect(formatTrigger("API_EVENT")).toBe("Api Event");
  });
});

describe("isLiveStatus", () => {
  it("identifies active running or queued states", () => {
    expect(isLiveStatus("RUNNING")).toBe(true);
    expect(isLiveStatus("QUEUED")).toBe(true);
  });

  it("identifies terminal and non-polling states", () => {
    expect(isLiveStatus("SUCCEEDED")).toBe(false);
    expect(isLiveStatus("FAILED")).toBe(false);
    expect(isLiveStatus("CANCELED")).toBe(false);
    expect(isLiveStatus("WAITING")).toBe(false);
  });
});

describe("executionStatusVariant & nodeStatusVariant", () => {
  it("maps execution statuses to correct badge variants", () => {
    expect(executionStatusVariant("SUCCEEDED")).toBe("success");
    expect(executionStatusVariant("FAILED")).toBe("destructive");
    expect(executionStatusVariant("WAITING")).toBe("warning");
    expect(executionStatusVariant("RUNNING")).toBe("default");
    expect(executionStatusVariant("QUEUED")).toBe("secondary");
    expect(executionStatusVariant("CANCELED")).toBe("outline");
  });

  it("maps node statuses to correct badge variants", () => {
    expect(nodeStatusVariant("SUCCEEDED")).toBe("success");
    expect(nodeStatusVariant("FAILED")).toBe("destructive");
    expect(nodeStatusVariant("WAITING")).toBe("warning");
    expect(nodeStatusVariant("RUNNING")).toBe("default");
    expect(nodeStatusVariant("PENDING")).toBe("secondary");
    expect(nodeStatusVariant("SKIPPED")).toBe("outline");
  });
});

describe("getStatusConfig", () => {
  it("returns appropriate tokens and tones for statuses", () => {
    const succeeded = getStatusConfig("SUCCEEDED");
    expect(succeeded.label).toBe("Succeeded");
    expect(succeeded.tone).toBe("success");

    const failed = getStatusConfig("FAILED");
    expect(failed.label).toBe("Failed");
    expect(failed.tone).toBe("danger");

    const running = getStatusConfig("RUNNING");
    expect(running.label).toBe("Running");
    expect(running.tone).toBe("active");
  });
});

describe("getHealthTone", () => {
  it("calculates tones based on percentage thresholds", () => {
    expect(getHealthTone(99.5)).toBe("success");
    expect(getHealthTone(96)).toBe("success");
    expect(getHealthTone(92)).toBe("warning");
    expect(getHealthTone(80)).toBe("warning");
    expect(getHealthTone(75)).toBe("danger");
    expect(getHealthTone(null)).toBe("neutral");
  });
});

describe("getHealthLabel", () => {
  it("maps rates to human labels and handles null", () => {
    expect(getHealthLabel(99.5)).toBe("Excellent");
    expect(getHealthLabel(96)).toBe("Healthy");
    expect(getHealthLabel(85)).toBe("Needs attention");
    expect(getHealthLabel(75)).toBe("At risk");
    expect(getHealthLabel(null)).toBe("No data");
    expect(getHealthLabel(undefined)).toBe("No data");
  });
});

describe("formatBucketLabel and formatDateTime", () => {
  it("handles invalid dates gracefully", () => {
    expect(formatBucketLabel("invalid-date", false)).toBe("");
    expect(formatDateTime("invalid-date")).toBe("—");
    expect(formatRelativeTime("invalid-date")).toBe("—");
  });

  it("formats valid ISO dates", () => {
    const iso = "2026-08-15T12:00:00Z";
    expect(formatBucketLabel(iso, false)).toBeTruthy();
    expect(formatDateTime(iso)).toBeTruthy();
  });
});
