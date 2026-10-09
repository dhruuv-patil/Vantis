import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConnectIntegrationDialog } from "../components/app/connect-integration-dialog";
import * as apiModule from "../lib/api";

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  );
}

describe("ConnectIntegrationDialog Component", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders docs link and setup guide for GitHub provider", async () => {
    vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
      providers: [
        {
          type: "github",
          name: "GitHub Actions",
          description: "Sync workflow runs from GitHub Actions.",
          available: true,
          docsUrl: "https://github.com/settings/tokens",
          capabilities: {
            workflowDiscovery: true,
            executionHistory: true,
            nodeExecutionData: false,
            incrementalSync: false,
            webhooks: true,
            tracing: false,
          },
          credentialFields: [
            {
              key: "token",
              label: "Personal Access Token",
              secret: true,
              placeholder: "ghp_...",
              example: "",
              help: "Needs repo and workflow scopes.",
            },
          ],
        },
      ],
    });

    renderWithClient(
      <ConnectIntegrationDialog
        open={true}
        onOpenChange={() => {}}
        providerType="github"
      />,
    );

    // Wait for the modal and content to load
    await waitFor(() => {
      expect(screen.getByText("Connect GitHub Actions")).toBeInTheDocument();
    });

    // Check setup guide card is present
    expect(
      screen.getByText("How to get credentials & connect"),
    ).toBeInTheDocument();

    // Check external docs link button is rendered with target="_blank"
    const docsLink = screen.getByRole("link", {
      name: /github personal access tokens/i,
    });
    expect(docsLink).toBeInTheDocument();
    expect(docsLink).toHaveAttribute("href", "https://github.com/settings/tokens");
    expect(docsLink).toHaveAttribute("target", "_blank");

    // Check setup steps rendered from provider-meta
    expect(
      screen.getByText(/Developer Settings/i),
    ).toBeInTheDocument();
  });

  it("toggles password secret visibility between password and text", async () => {
    vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
      providers: [
        {
          type: "openai",
          name: "OpenAI",
          description: "Connect OpenAI models.",
          available: true,
          docsUrl: "https://platform.openai.com/api-keys",
          capabilities: {
            workflowDiscovery: false,
            executionHistory: false,
            nodeExecutionData: false,
            incrementalSync: false,
            webhooks: false,
            tracing: false,
          },
          credentialFields: [
            {
              key: "apiKey",
              label: "API Key",
              secret: true,
              placeholder: "sk-...",
              example: "",
              help: "OpenAI secret key",
            },
          ],
        },
      ],
    });

    renderWithClient(
      <ConnectIntegrationDialog
        open={true}
        onOpenChange={() => {}}
        providerType="openai"
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Connect OpenAI")).toBeInTheDocument();
    });

    const input = screen.getByPlaceholderText("sk-...");
    expect(input).toHaveAttribute("type", "password");

    // Click show password toggle
    const toggleButton = screen.getByRole("button", {
      name: /show value/i,
    });
    fireEvent.click(toggleButton);

    expect(input).toHaveAttribute("type", "text");

    // Click again to hide
    const hideButton = screen.getByRole("button", {
      name: /hide value/i,
    });
    fireEvent.click(hideButton);

    expect(input).toHaveAttribute("type", "password");
  });

  it("submits the form and calls connectProvider with typed values", async () => {
    const connectProviderSpy = vi
      .spyOn(apiModule, "connectProvider")
      .mockResolvedValue({
        id: "int-123",
        type: "openai",
        name: "OpenAI",
        status: "connected",
        hint: "sk42",
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
      });

    vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
      providers: [
        {
          type: "openai",
          name: "OpenAI",
          description: "Connect OpenAI models.",
          available: true,
          docsUrl: "https://platform.openai.com/api-keys",
          capabilities: {
            workflowDiscovery: false,
            executionHistory: false,
            nodeExecutionData: false,
            incrementalSync: false,
            webhooks: false,
            tracing: false,
          },
          credentialFields: [
            {
              key: "apiKey",
              label: "API Key",
              secret: true,
              placeholder: "sk-...",
              example: "",
              help: "OpenAI secret key",
            },
          ],
        },
      ],
    });

    renderWithClient(
      <ConnectIntegrationDialog
        open={true}
        onOpenChange={() => {}}
        providerType="openai"
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Connect OpenAI")).toBeInTheDocument();
    });

    const input = screen.getByPlaceholderText("sk-...");
    fireEvent.change(input, { target: { value: "sk-testsecretkey123" } });

    const submitBtn = screen.getByRole("button", {
      name: /^connect$/i,
    });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(connectProviderSpy).toHaveBeenCalledWith({
        type: "openai",
        name: "OpenAI",
        config: {
          apiKey: "sk-testsecretkey123",
        },
      });
    });
  });

  it("renders inline error alert when connectProvider fails", async () => {
    vi.spyOn(apiModule, "connectProvider").mockRejectedValue({
      message: "The integration credentials or configuration are invalid.",
      code: "INTEGRATION_INVALID",
    });
    vi.spyOn(apiModule, "connectIntegration").mockRejectedValue({
      message: "The integration credentials or configuration are invalid.",
      code: "INTEGRATION_INVALID",
    });

    vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
      providers: [
        {
          type: "slack",
          name: "Slack",
          description: "Send alerts to a Slack channel.",
          available: true,
          docsUrl: "https://api.slack.com/messaging/webhooks",
          capabilities: {
            workflowDiscovery: false,
            executionHistory: false,
            nodeExecutionData: false,
            incrementalSync: false,
            webhooks: true,
            tracing: false,
          },
          credentialFields: [
            {
              key: "webhookUrl",
              label: "Webhook URL",
              secret: true,
              placeholder: "https://hooks.slack.com/services/...",
              example: "",
              help: "Slack webhook URL",
            },
          ],
        },
      ],
    });

    renderWithClient(
      <ConnectIntegrationDialog
        open={true}
        onOpenChange={() => {}}
        providerType="slack"
      />,
    );

    await waitFor(() => {
      expect(screen.getByText("Connect Slack")).toBeInTheDocument();
    });

    const input = screen.getByPlaceholderText("https://hooks.slack.com/services/...");
    fireEvent.change(input, { target: { value: "https://invalid-url.com" } });

    const submitBtn = screen.getByRole("button", {
      name: /^connect$/i,
    });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText(/The integration credentials or configuration are invalid/i),
      ).toBeInTheDocument();
    });
  });
});
