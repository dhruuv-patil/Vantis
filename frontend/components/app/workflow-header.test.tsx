import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import * as React from "react";

import { WorkflowHeader } from "@/components/app/workflow-header";
import type { WorkflowDetail } from "@/types";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
  }),
}));

const mockWorkflow: WorkflowDetail = {
  id: "wf-123",
  name: "Order Processing",
  description: "Processes customer orders",
  status: "PUBLISHED",
  latestVersion: 2,
  graph: { nodes: [], edges: [] },
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
};

function renderHeader(props: Partial<React.ComponentProps<typeof WorkflowHeader>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <WorkflowHeader
        workflow={mockWorkflow}
        version={2}
        dirty={false}
        canRun={true}
        onValidate={vi.fn()}
        onGenerate={vi.fn()}
        onSave={vi.fn()}
        onPublish={vi.fn()}
        onDelete={vi.fn()}
        savePending={false}
        publishPending={false}
        deletePending={false}
        onWebhook={vi.fn()}
        hasWebhookTrigger={true}
        activeView="editor"
        onViewChange={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  );
}

describe("WorkflowHeader", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders the validate button like webhook, save, and delete buttons", () => {
    const onValidate = vi.fn();
    renderHeader({ onValidate });

    const validateBtn = screen.getByRole("button", { name: "Validate workflow" });
    const webhookBtn = screen.getByRole("button", { name: "Manage webhook" });
    const saveBtn = screen.getByRole("button", { name: "Save" });
    const deleteBtn = screen.getByRole("button", { name: "Delete workflow" });

    expect(validateBtn).toBeInTheDocument();
    expect(webhookBtn).toBeInTheDocument();
    expect(saveBtn).toBeInTheDocument();
    expect(deleteBtn).toBeInTheDocument();

    // Verify all 4 icon buttons share consistent size and styling
    expect(validateBtn.className).toContain("size-8");
    expect(webhookBtn.className).toContain("size-8");
    expect(saveBtn.className).toContain("size-8");
    expect(deleteBtn.className).toContain("size-8");

    // Clicking validate triggers onValidate
    fireEvent.click(validateBtn);
    expect(onValidate).toHaveBeenCalledTimes(1);
  });

  it("handles the run button click when canRun is true", () => {
    const onRun = vi.fn();
    renderHeader({ canRun: true, onRun });

    const runBtn = screen.getByRole("button", { name: "Run" });
    expect(runBtn).not.toBeDisabled();

    fireEvent.click(runBtn);
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  it("disables the run button when canRun is false", () => {
    renderHeader({ canRun: false, version: null });

    const runBtn = screen.getByRole("button", { name: "Run" });
    expect(runBtn).toBeDisabled();
  });
});
