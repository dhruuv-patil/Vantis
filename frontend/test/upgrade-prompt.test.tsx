import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  UpgradePrompt,
  isUpgradePromptDismissed,
  dismissUpgradePrompt,
  resetUpgradePromptDismissal,
  type UpgradeTrigger,
} from "../components/billing/upgrade-prompt";
import type { PlanLimitDetail } from "../types";

// Mock next/navigation
const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: mockPush,
  }),
}));

describe("UpgradePrompt Component", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  beforeEach(() => {
    sessionStorage.clear();
    mockPush.mockClear();
  });

  const triggers: UpgradeTrigger[] = [
    "workflows",
    "executions",
    "ai",
    "seats",
    "retention",
    "quota_warning",
  ];

  it("renders correctly for all six trigger points", () => {
    for (const trigger of triggers) {
      cleanup();
      sessionStorage.clear();
      render(<UpgradePrompt trigger={trigger} />);
      expect(screen.getByRole("region")).toBeInTheDocument();
      expect(screen.getByText("Dismiss")).toBeInTheDocument();
      expect(screen.getByText("Upgrade")).toBeInTheDocument();
    }
  });

  it("consumes machine-readable PlanLimitDetail without message parsing", () => {
    const detail: PlanLimitDetail = {
      limitKey: "activeWorkflows",
      current: 10,
      max: 10,
      planId: "PRIVATE_BETA",
      suggestedPlan: "PRO",
    };

    render(<UpgradePrompt trigger="workflows" structuredError={detail} />);

    expect(screen.getByText("PRO Upgrade Recommended")).toBeInTheDocument();
    expect(
      screen.getByText(
        "You have reached the activeWorkflows limit (10 / 10) on the PRIVATE_BETA plan. Upgrade to PRO for higher capacity.",
      ),
    ).toBeInTheDocument();
  });

  it("persists dismissal in sessionStorage and does not re-render in the same session", () => {
    expect(isUpgradePromptDismissed("executions")).toBe(false);

    const onDismiss = vi.fn();
    const { unmount } = render(
      <UpgradePrompt trigger="executions" onDismiss={onDismiss} />,
    );

    const dismissBtn = screen.getByText("Dismiss");
    fireEvent.click(dismissBtn);

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(isUpgradePromptDismissed("executions")).toBe(true);
    expect(
      sessionStorage.getItem("vantis_upgrade_prompt_dismissed_executions"),
    ).toBe("true");

    unmount();

    // Re-rendering in same session returns null (dismissed)
    const { container } = render(<UpgradePrompt trigger="executions" />);
    expect(container.firstChild).toBeNull();
  });

  it("can be reset after session ends or manual reset", () => {
    dismissUpgradePrompt("ai");
    expect(isUpgradePromptDismissed("ai")).toBe(true);

    resetUpgradePromptDismissal("ai");
    expect(isUpgradePromptDismissed("ai")).toBe(false);

    const { container } = render(<UpgradePrompt trigger="ai" />);
    expect(container.querySelector("[role='region']")).toBeInTheDocument();
  });

  it("renders dialog variant properly and responds to dismissal", () => {
    const onOpenChange = vi.fn();
    render(
      <UpgradePrompt
        trigger="seats"
        variant="dialog"
        open={true}
        onOpenChange={onOpenChange}
      />,
    );

    expect(screen.getByText("Team Seat Limit Reached")).toBeInTheDocument();
    const dismissBtn = screen.getByText("Dismiss for session");
    fireEvent.click(dismissBtn);

    expect(isUpgradePromptDismissed("seats")).toBe(true);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("navigates to /billing when View Plans & Upgrade is clicked in dialog variant", () => {
    render(<UpgradePrompt trigger="workflows" variant="dialog" open={true} />);

    const upgradeBtn = screen.getByText("View Plans & Upgrade");
    fireEvent.click(upgradeBtn);

    expect(mockPush).toHaveBeenCalledWith("/billing");
    expect(isUpgradePromptDismissed("workflows")).toBe(true);
  });

  it("stays dismissed when remounting, navigating away and back, and retriggering in same session", () => {
    // 1. Initial mount and trigger
    const { unmount } = render(<UpgradePrompt trigger="workflows" />);
    expect(screen.getByRole("region")).toBeInTheDocument();

    // 2. Dismiss prompt
    fireEvent.click(screen.getByText("Dismiss"));
    expect(isUpgradePromptDismissed("workflows")).toBe(true);

    // 3. Simulate navigating away (unmount)
    unmount();

    // 4. Simulate navigating back (remount)
    const { unmount: unmount2, container: container2 } = render(
      <UpgradePrompt trigger="workflows" />,
    );
    expect(container2.firstChild).toBeNull();
    unmount2();

    // 5. Retrigger with new structured error in same session
    const detail: PlanLimitDetail = {
      limitKey: "activeWorkflows",
      current: 10,
      max: 10,
      planId: "PRIVATE_BETA",
      suggestedPlan: "PRO",
    };
    const { container: container3 } = render(
      <UpgradePrompt trigger="workflows" structuredError={detail} />,
    );
    expect(container3.firstChild).toBeNull();
  });

  it("dismissing one trigger does not suppress unrelated triggers", () => {
    // Dismiss "workflows"
    dismissUpgradePrompt("workflows");
    expect(isUpgradePromptDismissed("workflows")).toBe(true);

    // Verify all other 5 triggers remain completely unsuppressed
    const otherTriggers: UpgradeTrigger[] = [
      "executions",
      "ai",
      "seats",
      "retention",
      "quota_warning",
    ];

    for (const other of otherTriggers) {
      expect(isUpgradePromptDismissed(other)).toBe(false);
      cleanup();
      const { container } = render(<UpgradePrompt trigger={other} />);
      expect(container.querySelector("[role='region']")).toBeInTheDocument();
    }
  });

  it("handles storage errors gracefully without crashing the application", () => {
    // Mock sessionStorage methods to throw SecurityError or QuotaExceededError
    const originalGetItem = sessionStorage.getItem;
    const originalSetItem = sessionStorage.setItem;

    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("The operation is insecure.", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("Quota exceeded.", "QuotaExceededError");
    });

    expect(() => isUpgradePromptDismissed("executions")).not.toThrow();
    expect(isUpgradePromptDismissed("executions")).toBe(false);

    expect(() => dismissUpgradePrompt("executions")).not.toThrow();

    // Render component when storage is throwing
    expect(() => {
      render(<UpgradePrompt trigger="executions" />);
    }).not.toThrow();

    expect(screen.getByRole("region")).toBeInTheDocument();

    // Clicking dismiss should still dismiss locally without throwing
    const dismissBtn = screen.getByText("Dismiss");
    expect(() => {
      fireEvent.click(dismissBtn);
    }).not.toThrow();

    Storage.prototype.getItem = originalGetItem;
    Storage.prototype.setItem = originalSetItem;
  });

  it("suggests BUSINESS when on PRO, and PRO when on FREE or PRIVATE_BETA", () => {
    // On PRO plan without structured error -> suggests BUSINESS with accurate quotas
    const { unmount: unmountPro } = render(
      <UpgradePrompt trigger="workflows" currentPlan="PRO" />,
    );
    expect(screen.getByText("BUSINESS")).toBeInTheDocument();
    expect(
      screen.getByText(
        "You have reached your active workflows limit. Upgrade to Business for up to 1,000 active workflows.",
      ),
    ).toBeInTheDocument();
    unmountPro();
    sessionStorage.clear();

    // On PRIVATE_BETA plan -> suggests PRO
    const { unmount: unmountBeta } = render(
      <UpgradePrompt trigger="executions" currentPlan="PRIVATE_BETA" />,
    );
    expect(screen.getByText("PRO")).toBeInTheDocument();
    expect(
      screen.getByText(
        "You have reached your monthly execution allowance. Upgrade to Pro for 50,000 monthly executions.",
      ),
    ).toBeInTheDocument();
    unmountBeta();
    sessionStorage.clear();

    // On FREE plan -> suggests PRO
    render(<UpgradePrompt trigger="seats" currentPlan="FREE" />);
    expect(screen.getByText("PRO")).toBeInTheDocument();
    expect(
      screen.getByText(
        "You have reached the maximum team members for your plan. Upgrade to Pro for up to 10 team seats.",
      ),
    ).toBeInTheDocument();
  });
});
