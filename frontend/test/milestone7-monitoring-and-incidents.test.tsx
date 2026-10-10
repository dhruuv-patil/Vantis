import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConnectIntegrationDialog } from "../components/app/connect-integration-dialog";
import IntegrationsPage from "../app/(app)/integrations/page";
import IncidentsPage from "../app/(app)/incidents/page";
import * as apiModule from "../lib/api";
import type { Incident, Integration, ProviderInfo } from "../types";

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

describe("Milestone 7: Monitoring UI, Multi-Instance Integrations & Incidents", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.HTMLElement.prototype.hasPointerCapture = () => false;
    window.HTMLElement.prototype.setPointerCapture = () => {};
    window.HTMLElement.prototype.releasePointerCapture = () => {};
    window.HTMLElement.prototype.scrollIntoView = () => {};
  });

  afterEach(() => {
    cleanup();
  });

  describe("ConnectIntegrationDialog Multi-Instance Support", () => {
    it("submits with custom instance name when provided by user", async () => {
      const connectProviderSpy = vi
        .spyOn(apiModule, "connectProvider")
        .mockResolvedValue({
          id: "int-custom-n8n",
          type: "n8n",
          name: "Production n8n Cluster",
          status: "connected",
          hint: "n8n4",
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        });

      vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
        providers: [
          {
            type: "n8n",
            name: "n8n",
            description: "Self-hosted workflow automation.",
            available: true,
            docsUrl: "https://docs.n8n.io",
            capabilities: {
              workflowDiscovery: true,
              executionHistory: true,
              nodeExecutionData: true,
              incrementalSync: true,
              webhooks: false,
              tracing: true,
            },
            credentialFields: [
              {
                key: "baseUrl",
                label: "Base URL",
                secret: false,
                placeholder: "https://n8n.example.com",
                example: "https://n8n.example.com",
                help: "n8n instance URL",
              },
              {
                key: "apiKey",
                label: "API Key",
                secret: true,
                placeholder: "API Key",
                example: "",
                help: "n8n API Key",
              },
            ],
          },
        ],
      });

      renderWithClient(
        <ConnectIntegrationDialog
          open={true}
          onOpenChange={() => {}}
          providerType="n8n"
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("Connect n8n")).toBeInTheDocument();
      });

      // Type custom instance name
      const nameInput = screen.getByLabelText(/Instance \/ Connection Name/i);
      fireEvent.change(nameInput, { target: { value: "Production n8n Cluster" } });

      // Fill credentials
      const baseUrlInput = screen.getByPlaceholderText("https://n8n.example.com");
      fireEvent.change(baseUrlInput, { target: { value: "https://n8n.production.corp" } });

      const apiKeyInput = screen.getByPlaceholderText("API Key");
      fireEvent.change(apiKeyInput, { target: { value: "n8n_sec_key_12345" } });

      const submitBtn = screen.getByRole("button", { name: /^connect$/i });
      fireEvent.click(submitBtn);

      await waitFor(() => {
        expect(connectProviderSpy).toHaveBeenCalledWith({
          type: "n8n",
          name: "Production n8n Cluster",
          config: {
            baseUrl: "https://n8n.production.corp",
            apiKey: "n8n_sec_key_12345",
          },
        });
      });
    });
  });

  describe("IntegrationsPage Multi-Instance Rendering", () => {
    it("renders multiple connected instances of the same provider with health badges", async () => {
      const mockIntegrations: any[] = [
        {
          id: "int-1",
          name: "Production n8n",
          type: "n8n",
          category: "AUTOMATION",
          status: "connected",
          hint: "prod",
          healthStatus: "healthy",
          baseUrl: "https://n8n.prod.internal",
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
        {
          id: "int-2",
          name: "Staging n8n",
          type: "n8n",
          category: "AUTOMATION",
          status: "connected",
          hint: "stg1",
          healthStatus: "degraded",
          baseUrl: "https://n8n.staging.internal",
          createdAt: "2026-01-02T00:00:00Z",
          updatedAt: "2026-01-02T00:00:00Z",
        },
      ];

      const mockProviders: ProviderInfo[] = [
        {
          type: "n8n",
          name: "n8n",
          description: "Self-hosted workflow automation.",
          available: true,
          capabilities: {
            workflowDiscovery: true,
            executionHistory: true,
            nodeExecutionData: true,
            incrementalSync: true,
            webhooks: false,
            tracing: true,
          },
          credentialFields: [],
        },
      ];

      vi.spyOn(apiModule, "fetchIntegrations").mockResolvedValue({
        integrations: mockIntegrations,
      });
      vi.spyOn(apiModule, "fetchProviders").mockResolvedValue({
        providers: mockProviders,
      });
      vi.spyOn(apiModule, "fetchSyncStatus").mockResolvedValue({
        status: "HEALTHY",
        lastSyncAt: "2026-01-02T00:00:00Z",
        lastSuccessfulSyncAt: "2026-01-02T00:00:00Z",
        error: null,
      });

      renderWithClient(<IntegrationsPage />);

      await waitFor(() => {
        expect(screen.getByText("Production n8n")).toBeInTheDocument();
        expect(screen.getByText("Staging n8n")).toBeInTheDocument();
      });

      // Health badges
      expect(screen.getByText("Healthy")).toBeInTheDocument();
      expect(screen.getByText("Degraded")).toBeInTheDocument();

      // Check catalog shows count and "Add another instance"
      expect(screen.getByText("2 connected")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /add another instance/i })).toBeInTheDocument();
    });
  });

  describe("IncidentsPage Rendering and Recovery Guard", () => {
    it("renders incident list and displays 409 recovery guard modal when resolution is blocked", async () => {
      const mockIncidents: any[] = [
        {
          id: "inc-101",
          organizationId: "org-1",
          title: "n8n Output Schema Drift in Order Sync",
          severity: "HIGH",
          status: "OPEN",
          fingerprint: "order_sync:schema_drift:missing_customer_id",
          workflowId: "wf-order-sync",
          workflowName: "Order Sync Pipeline",
          executionId: "exec-99",
          anomalyId: "anom-42",
          firstSeenAt: "2026-01-01T10:00:00Z",
          lastSeenAt: "2026-01-01T11:00:00Z",
          eventCount: 3,
          description: "Detected missing customer_id key in 3 consecutive runs.",
          createdAt: "2026-01-01T10:00:00Z",
          updatedAt: "2026-01-01T11:00:00Z",
        },
      ];

      vi.spyOn(apiModule, "fetchIncidents").mockResolvedValue(mockIncidents);

      // Simulate 409 Conflict from backend IncidentService.resolve()
      vi.spyOn(apiModule, "resolveIncident").mockRejectedValue({
        status: 409,
        response: {
          status: 409,
          data: {
            code: "RECOVERY_UNVERIFIED",
            message: "Incident cannot be closed: anomaly has not met consecutive healthy run verification threshold.",
          },
        },
      });

      renderWithClient(<IncidentsPage />);

      await waitFor(() => {
        expect(screen.getByText("n8n Output Schema Drift in Order Sync")).toBeInTheDocument();
      });

      expect(screen.getByText("High")).toBeInTheDocument();
      expect(screen.getByText("Open")).toBeInTheDocument();
      expect(screen.getByText(/order_sync:schema_drift/)).toBeInTheDocument();

      // Open dropdown menu
      const menuTrigger = screen.getByRole("button", { name: /open menu/i });
      fireEvent.pointerDown(menuTrigger, { button: 0, ctrlKey: false });

      // Click "Resolve incident"
      const resolveOption = await screen.findByText(/resolve incident/i);
      fireEvent.click(resolveOption);

      // Verify the 409 Recovery Verification Blocked modal appears
      await waitFor(() => {
        expect(screen.getByText("Recovery Verification Required")).toBeInTheDocument();
        expect(
          screen.getByText(/To prevent premature resolution and repeated incident alerts/i),
        ).toBeInTheDocument();
      });

      // Confirm button to dismiss modal
      const dismissBtns = screen.getAllByRole("button", { name: /^close$/i });
      expect(dismissBtns.length).toBeGreaterThan(0);
      fireEvent.click(dismissBtns[0]);

      await waitFor(() => {
        expect(screen.queryByText("Recovery Verification Required")).not.toBeInTheDocument();
      });
    });
  });
});
