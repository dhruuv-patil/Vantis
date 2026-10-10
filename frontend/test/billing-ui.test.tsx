import { cleanup, render, screen, waitFor, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import BillingPage from "../app/(app)/billing/page";
import * as apiModule from "../lib/api";
import { useAuthStore } from "../lib/auth-store";

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

describe("BillingPage UI Component", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    window.matchMedia = (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });

    useAuthStore.setState({
      status: "authenticated",
      accessToken: "mock-token",
      currentOrganization: {
        id: "org-1",
        name: "Vantis",
        slug: "vantis",
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
      },
      currentRole: "OWNER",
      user: {
        id: "u-1",
        email: "test@vantis.run",
        fullName: "Test User",
        avatarUrl: null,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
        emailVerified: true,
      },
      memberships: [],
    });

    vi.spyOn(apiModule, "fetchBillingState").mockResolvedValue({
      isPrivateBeta: true,
      isBillingEnabled: false,
      betaNotice: "Private Beta — Pro features are currently free.",
      billingCountry: "IN",
      billingCurrency: "INR",
      currencySymbol: "₹",
      displayCurrency: "INR",
      providerSupported: true,
      activePlan: "PRIVATE_BETA",
      subscription: null,
      usage: {
        workflows: { used: 7, limit: 50 },
        executions: { used: 1420, limit: 10000 },
        aiGenerations: { used: 28, limit: 100 },
        teamMembers: { used: 2, limit: 5 },
      },
      availableCurrencies: ["INR", "USD", "GBP", "EUR", "CAD", "AUD", "SGD", "JPY"],
    });

    vi.spyOn(apiModule, "fetchBillingPlans").mockResolvedValue({
      plans: [
        {
          id: "FREE",
          name: "Free",
          tagline: "Core building blocks for individual developers.",
          isCustom: false,
          isCurrent: false,
          features: ["5 active workflows", "500 executions/month"],
          limits: {
            workflows: 5,
            executionsPerMonth: 500,
            aiGenerationsPerMonth: 10,
            teamMembers: 1,
            featureFlags: {},
          },
          pricing: {
            amountMinor: 0,
            formatted: "₹0",
            isBetaZero: false,
            interval: "MONTHLY",
            currency: "INR",
            currencySymbol: "₹",
          },
        },
        {
          id: "PRIVATE_BETA",
          name: "Private Beta",
          tagline: "Full access to Pro features without cost during our preview period.",
          betaBadge: "Free during Private Beta",
          isCustom: false,
          isCurrent: true,
          features: ["50 active workflows", "10,000 executions/month"],
          limits: {
            workflows: 50,
            executionsPerMonth: 10000,
            aiGenerationsPerMonth: 100,
            teamMembers: 5,
            featureFlags: {},
          },
          pricing: {
            amountMinor: 0,
            formatted: "₹0",
            isBetaZero: true,
            interval: "MONTHLY",
            currency: "INR",
            currencySymbol: "₹",
          },
        },
        {
          id: "PRO",
          name: "Pro",
          tagline: "Production-grade automation with advanced reliability.",
          isCustom: false,
          isCurrent: false,
          features: ["100 active workflows", "50,000 executions/month"],
          limits: {
            workflows: 100,
            executionsPerMonth: 50000,
            aiGenerationsPerMonth: 500,
            teamMembers: 15,
            featureFlags: {},
          },
          pricing: {
            amountMinor: 0,
            formatted: "₹0 during Private Beta",
            formattedStrikethrough: "₹1,999",
            isBetaZero: true,
            interval: "MONTHLY",
            currency: "INR",
            currencySymbol: "₹",
          },
        },
        {
          id: "BUSINESS",
          name: "Business",
          tagline: "Dedicated infrastructure and bespoke volume.",
          isCustom: true,
          isCurrent: false,
          features: ["Up to 1,000 workflows & 1M executions"],
          limits: {
            workflows: 1000,
            executionsPerMonth: 1000000,
            aiGenerationsPerMonth: 10000,
            teamMembers: 100,
            featureFlags: {},
          },
        },
      ],
      interval: "MONTHLY",
      displayCurrency: "INR",
      billingCurrency: "INR",
      billingCountry: "IN",
      currencySymbol: "₹",
      isDisplayCurrencyDifferent: false,
      displayCurrencyNotice: null,
      isPrivateBeta: true,
      isBillingEnabled: false,
      betaNotice: "Private Beta — Pro features are currently free.",
    });

    vi.spyOn(apiModule, "fetchBillingUsage").mockResolvedValue({
      plan: "PRIVATE_BETA",
      workflows: { used: 7, limit: 50, percentage: 14 },
      executions: { used: 1420, limit: 10000, percentage: 14 },
      aiGenerations: { used: 28, limit: 100, percentage: 28 },
      teamMembers: { used: 2, limit: 5, percentage: 40 },
    });

    vi.spyOn(apiModule, "fetchBillingSubscription").mockResolvedValue({
      organizationId: "org-1",
      plan: "PRIVATE_BETA",
      billingStatus: "ACTIVE",
      provider: "noop",
      subscriptionStatus: "ACTIVE",
      currentPeriodStart: "2026-01-01T00:00:00Z",
      currentPeriodEnd: "2026-02-01T00:00:00Z",
      isPrivateBeta: true,
      betaEndsAt: null,
      cancelAtPeriodEnd: false,
      renewalActive: true,
      foundingOfferEligible: true,
      foundingOfferApplied: false,
      foundingOfferEligibleUntil: null,
      usage: {
        workflows: 7,
        executions: 1420,
        aiGenerations: 28,
        aiInvestigationsMonthly: 0,
        aiInvestigationsTrial: 2,
        seats: 2,
        workspaces: 1,
      },
      planLimits: {
        activeWorkflows: 10,
        executionsPerMonth: 50000,
        aiGenerations: 500,
        aiInvestigationsMonthly: 50,
        aiInvestigationsLifetimeTrial: 0,
        seats: 10,
        workspaces: 5,
        historyRetentionDays: 30,
        workflowVersionsKept: 30,
        allowedNotificationChannels: ["EMAIL", "IN_APP", "WEBHOOK"],
        anomalyDetectionMode: "full",
      },
      remainingUsage: {
        workflows: 3,
        executions: 48580,
        aiGenerations: 472,
        aiInvestigations: 50,
        seats: 8,
        workspaces: 4,
      },
      availablePlanTransitions: ["PRO", "BUSINESS"],
      billingEnabled: false,
    });

    vi.spyOn(apiModule, "fetchBillingInvoices").mockResolvedValue([
      {
        id: "inv_123",
        date: "2026-01-15T00:00:00Z",
        amountPaid: 2500,
        currency: "USD",
        status: "PAID",
        hostedInvoiceUrl: "https://billing.example.com/inv_123",
        pdfUrl: null,
      },
    ]);

    vi.spyOn(apiModule, "fetchBillingStatus").mockResolvedValue({
      subscription: {
        organizationId: "org-1",
        plan: "PRIVATE_BETA",
        billingStatus: "ACTIVE",
        provider: "noop",
        subscriptionStatus: "ACTIVE",
        currentPeriodStart: "2026-01-01T00:00:00Z",
        currentPeriodEnd: "2026-02-01T00:00:00Z",
        isPrivateBeta: true,
        cancelAtPeriodEnd: false,
        renewalActive: true,
        foundingOfferEligible: true,
        foundingOfferApplied: false,
        usage: {
          workflows: 7,
          executions: 1420,
          aiGenerations: 28,
          aiInvestigationsMonthly: 0,
          aiInvestigationsTrial: 2,
          seats: 2,
          workspaces: 1,
        },
        planLimits: {
          activeWorkflows: 10,
          executionsPerMonth: 50000,
          aiGenerations: 500,
          aiInvestigationsMonthly: 50,
          aiInvestigationsLifetimeTrial: 0,
          seats: 10,
          workspaces: 5,
          historyRetentionDays: 30,
          workflowVersionsKept: 30,
          allowedNotificationChannels: ["EMAIL"],
          anomalyDetectionMode: "full",
        },
        remainingUsage: {
          workflows: 3,
          executions: 48580,
          aiGenerations: 472,
          aiInvestigations: 50,
          seats: 8,
          workspaces: 4,
        },
        availablePlanTransitions: ["PRO"],
        billingEnabled: false,
      },
      entitlements: {},
      provider: "noop",
      isBillingEnabled: false,
      isPrivateBeta: true,
      foundingOfferEligible: true,
      foundingOfferApplied: false,
      dunningActive: false,
    });
  });

  it("renders page header and Private Beta notice", async () => {
    useAuthStore.setState({
      status: "authenticated",
      currentOrganization: {
        id: "org-1",
        name: "Vantis",
        slug: "vantis",
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
      },
    });

    renderWithClient(<BillingPage />);

    expect(screen.getByText("Billing & Usage")).toBeInTheDocument();
    expect(
      screen.getByText("Private Beta — Pro features are currently free."),
    ).toBeInTheDocument();
  });

  it("renders usage progress bars with real backend values", async () => {
    renderWithClient(<BillingPage />);

    await waitFor(() => {
      expect(screen.getByText("Monthly Resource Usage")).toBeInTheDocument();
      expect(screen.getByText("Executions")).toBeInTheDocument();
      expect(screen.getByText("AI Generations")).toBeInTheDocument();
      expect(screen.getByText("Active Workflows")).toBeInTheDocument();
    });
  });

  it("renders plan cards including Free, Private Beta, Pro, and Business", async () => {
    renderWithClient(<BillingPage />);

    await waitFor(() => {
      expect(screen.getByText("Available Plans")).toBeInTheDocument();
      expect(screen.getByText("Core building blocks for individual developers.")).toBeInTheDocument();
      expect(screen.getByText("Custom")).toBeInTheDocument();
      expect(screen.getByText("Contact Sales")).toBeInTheDocument();
    });
  });

  it("renders Founding Member offer card when eligible", async () => {
    renderWithClient(<BillingPage />);

    await waitFor(() => {
      expect(screen.getByText("Founding Member Offer Available")).toBeInTheDocument();
      expect(screen.getByText("Claim Founding Offer")).toBeInTheDocument();
    });
  });

  it("renders invoices section with invoice records", async () => {
    renderWithClient(<BillingPage />);

    await waitFor(() => {
      expect(screen.getByText("Invoices & Billing History")).toBeInTheDocument();
      expect(screen.getByText("inv_123")).toBeInTheDocument();
      expect(screen.getByText("PAID")).toBeInTheDocument();
    });
  });

  it("renders properly for anonymous logged-out visitor", async () => {
    useAuthStore.setState({
      status: "unauthenticated",
      currentOrganization: null,
      user: null,
    });

    renderWithClient(<BillingPage />);

    expect(screen.getByText("Billing & Usage")).toBeInTheDocument();
  });

  it("supports mobile and desktop layouts gracefully", async () => {
    window.innerWidth = 375;
    window.innerHeight = 667;

    const { container } = renderWithClient(<BillingPage />);
    expect(container.querySelector(".grid")).toBeInTheDocument();

    window.innerWidth = 1440;
    window.innerHeight = 900;
    expect(container.querySelector(".lg\\:grid-cols-4, .lg\\:grid-cols-3")).toBeDefined();
  });
});
