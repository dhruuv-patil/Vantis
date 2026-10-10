"use client";

import { useMemo, useState } from "react";
import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import {
  AlertCircle,
  ArrowUpRight,
  CheckCircle2,
  ChevronRight,
  Clock3,
  ExternalLink,
  Loader2,
  Plug,
  Plus,
  RefreshCw,
  Search,
} from "lucide-react";

import { formatDistanceToNow } from "date-fns";
import { toast } from "sonner";

import {
  disconnectIntegration,
  fetchIntegrations,
  fetchProviders,
  fetchSyncStatus,
  getErrorMessage,
  sendTestIntegration,
  syncIntegration,
  testIntegration,
} from "@/lib/api";

import { hasRole, useAuthStore } from "@/lib/auth-store";

import {
  getProviderCategory,
  getProviderDocsUrl,
  getProviderIcon,
} from "@/lib/provider-meta";

import type {
  Integration,
  ProviderInfo,
} from "@/types";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  CardGridSkeleton,
  CategoryPillsRow,
  EmptyState,
  ErrorState,
  PageContainer,
  PageHeader,
  Toolbar,
} from "@/components/app/page-primitives";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

import { ConnectIntegrationDialog } from "@/components/app/connect-integration-dialog";
import { WorkflowDiscovery } from "@/components/integrations/workflow-discovery";
import { ProviderIcon } from "@/components/integrations/provider-icon";

const CATEGORIES = [
  { label: "All", value: "All" },
  { label: "Communication", value: "COMMUNICATION" },
  { label: "Email", value: "EMAIL" },
  { label: "Developer", value: "DEVELOPER" },
  { label: "Project Mgmt", value: "PROJECT_MANAGEMENT" },
  { label: "CRM", value: "CRM" },
  { label: "Cloud", value: "CLOUD" },
  { label: "Storage", value: "STORAGE" },
  { label: "Productivity", value: "PRODUCTIVITY" },
  { label: "Payments", value: "PAYMENTS" },
  { label: "AI", value: "AI" },
  { label: "Automation", value: "AUTOMATION" },
  { label: "Universal", value: "UNIVERSAL" },
];

function HealthBadge({
  healthStatus,
  syncFailed,
}: {
  healthStatus?: string;
  syncFailed?: boolean;
}) {
  if (healthStatus === "degraded") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-amber-400/20 bg-amber-400/10 px-2 py-0.5 text-[11px] font-medium text-amber-300">
        <span className="size-1.5 rounded-full bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.7)]" />
        Degraded
      </span>
    );
  }
  if (healthStatus === "rate_limited") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-orange-400/20 bg-orange-400/10 px-2 py-0.5 text-[11px] font-medium text-orange-300">
        <span className="size-1.5 rounded-full bg-orange-400" />
        Rate limited
      </span>
    );
  }
  if (healthStatus === "auth_failed") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-red-500/20 bg-red-500/10 px-2 py-0.5 text-[11px] font-medium text-red-400">
        <span className="size-1.5 rounded-full bg-red-500" />
        Auth failed
      </span>
    );
  }
  if (healthStatus === "unreachable" || healthStatus === "sync_stalled") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-red-400/20 bg-red-400/10 px-2 py-0.5 text-[11px] font-medium text-red-300">
        <span className="size-1.5 rounded-full bg-red-400" />
        {healthStatus === "sync_stalled" ? "Sync stalled" : "Unreachable"}
      </span>
    );
  }
  if (healthStatus === "backfilling") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-blue-400/20 bg-blue-400/10 px-2 py-0.5 text-[11px] font-medium text-blue-300">
        <span className="size-1.5 rounded-full bg-blue-400 animate-pulse" />
        Backfilling
      </span>
    );
  }
  if (syncFailed) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-amber-400/20 bg-amber-400/10 px-2 py-0.5 text-[11px] font-medium text-amber-300">
        <span className="size-1.5 rounded-full bg-amber-400" />
        Needs attention
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 rounded-md border border-emerald-400/20 bg-emerald-400/10 px-2 py-0.5 text-[11px] font-medium text-emerald-300">
      <span className="size-1.5 rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.7)]" />
      Healthy
    </span>
  );
}

function SyncStatus({
  integrationId,
}: {
  integrationId: string;
}) {
  const q = useQuery({
    queryKey: [
      "integrations",
      integrationId,
      "sync",
    ],
    queryFn: () =>
      fetchSyncStatus(integrationId),
    refetchInterval: 60_000,
  });

  if (q.isPending || !q.data) {
    return null;
  }

  const {
    status,
    lastSuccessfulSyncAt,
    error,
  } = q.data;

  const failed = status === "FAILED";

  const label = failed
    ? (error ? `Sync failed: ${error}` : "Sync failed")
    : status === "NEW"
      ? "Never synced"
      : lastSuccessfulSyncAt
        ? `Synced ${formatDistanceToNow(
            new Date(lastSuccessfulSyncAt),
            {
              addSuffix: true,
            },
          )}`
        : "Synced";

  return (
    <div
      className={`mt-3 inline-flex items-center gap-1.5 rounded-md border px-2.5 py-1 text-[11px] font-medium ${
        failed
          ? "border-red-400/20 bg-red-400/10 text-red-500 dark:text-red-300"
          : "border-border dark:border-white/[0.07] bg-muted/40 dark:bg-white/[0.025] text-muted-foreground dark:text-white/35"
      }`}
    >
      {failed ? (
        <AlertCircle className="size-3" />
      ) : (
        <Clock3 className="size-3" />
      )}

      {label}
    </div>
  );
}

interface ConnectedInstanceCardProps {
  integration: Integration;
  provider?: ProviderInfo;
  isAdmin: boolean;
  onReconnect: () => void;
  onDisconnect: (integration: Integration) => void;
}

function ConnectedInstanceCard({
  integration,
  provider,
  isAdmin,
  onReconnect,
  onDisconnect,
}: ConnectedInstanceCardProps) {
  const [workflowsOpen, setWorkflowsOpen] = useState(false);
  const [testing, setTesting] = useState(false);
  const [sendingTest, setSendingTest] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const queryClient = useQueryClient();

  const isDeliveryChannel = provider?.capabilities?.webhooks ?? false;
  const isWorkflowProvider = !isDeliveryChannel;
  const resolvedIcon = provider?.icon ?? getProviderIcon(integration.type);
  const category = provider?.category ?? getProviderCategory(integration.type);
  const docsUrl = provider?.docsUrl || getProviderDocsUrl(integration.type);

  const testMutation = useMutation({
    mutationFn: () => testIntegration(integration.id),
    onSuccess: (result) =>
      toast[result.success ? "success" : "error"](
        result.message ??
          (result.success ? "Connection is healthy." : "Connection test failed."),
      ),
    onError: (err) =>
      toast.error(getErrorMessage(err, "Test failed.")),
    onSettled: () => setTesting(false),
  });

  const sendTestMutation = useMutation({
    mutationFn: () => sendTestIntegration(integration.id),
    onSuccess: (result) =>
      toast[result.success ? "success" : "error"](
        result.message ??
          (result.success ? "Test event sent." : "Test event failed."),
      ),
    onError: (err) =>
      toast.error(getErrorMessage(err, "Test send failed.")),
    onSettled: () => setSendingTest(false),
  });

  const syncMutation = useMutation({
    mutationFn: () => syncIntegration(integration.id),
    onSuccess: (result) => {
      toast.success(
        `Synced ${result.workflows} workflows, ${result.executions} executions, ${result.events} events`,
      );
    },
    onError: (err) =>
      toast.error(getErrorMessage(err, "Sync failed.")),
    onSettled: () => {
      setSyncing(false);
      queryClient.invalidateQueries({ queryKey: ["integrations"] });
      queryClient.invalidateQueries({ queryKey: ["integrations", integration.id, "sync"] });
    },
  });

  const syncQuery = useQuery({
    queryKey: ["integrations", integration.id, "sync"],
    queryFn: () => fetchSyncStatus(integration.id),
    enabled: isWorkflowProvider,
    refetchInterval: 60_000,
  });

  const syncFailed = syncQuery.data?.status === "FAILED";
  const adminTitle = isAdmin ? undefined : "Requires an admin";

  return (
    <>
      <article className="group relative flex h-full min-h-[280px] flex-col overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-sm dark:border-white/[0.065] dark:bg-[#0a0a0c] dark:text-white transition-colors duration-200 hover:border-border-strong hover:bg-muted/40 dark:hover:border-white/[0.12] dark:hover:bg-[#111114]">
        <div className="pointer-events-none absolute -right-16 -top-16 size-36 rounded-full bg-primary/[0.03] blur-2xl transition-opacity group-hover:opacity-100" />

        <div className="relative flex flex-1 flex-col p-5">
          <div className="flex items-start justify-between gap-3">
            <div className="flex size-12 items-center justify-center rounded-lg border border-border bg-background dark:border-white/[0.08] dark:bg-white/[0.04]">
              <ProviderIcon
                provider={integration.type}
                name={integration.name}
                icon={resolvedIcon}
                className="size-6"
                theme="auto"
                fallbackInitials
              />
            </div>

            <div className="flex flex-wrap items-center justify-end gap-2">
              <span className="rounded-md border border-border bg-muted/50 dark:border-white/[0.07] dark:bg-white/[0.025] px-2 py-0.5 text-[10px] font-medium uppercase tracking-[0.08em] text-muted-foreground dark:text-white/45">
                {category.replaceAll("_", " ")}
              </span>

              {docsUrl && (
                <a
                  href={docsUrl}
                  target="_blank"
                  rel="noreferrer"
                  title="View API documentation & portal"
                  className="inline-flex size-6 items-center justify-center rounded-md border border-border dark:border-white/[0.08] bg-muted/40 dark:bg-white/[0.03] text-muted-foreground dark:text-white/40 hover:text-foreground dark:hover:text-white transition-colors"
                >
                  <ExternalLink className="size-3" />
                </a>
              )}

              <HealthBadge
                healthStatus={integration.healthStatus}
                syncFailed={syncFailed}
              />
            </div>
          </div>

          <div className="mt-4">
            <div className="flex items-center gap-2">
              <h3 title={integration.name} className="text-[15px] font-semibold tracking-[-0.01em] text-foreground dark:text-white/90">
                {integration.name}
              </h3>
              {provider && provider.name !== integration.name && (
                <span className="rounded bg-muted dark:bg-white/[0.05] px-1.5 py-0.5 text-[10px] text-muted-foreground dark:text-white/40">
                  {provider.name}
                </span>
              )}
            </div>

            {integration.baseUrl && (
              <p className="mt-1 text-xs font-mono text-muted-foreground dark:text-white/40 truncate" title={integration.baseUrl}>
                {integration.baseUrl}
              </p>
            )}

            <p title={provider?.description} className="mt-2 text-[13px] leading-5 text-muted-foreground dark:text-white/45 line-clamp-2">
              {provider?.description ?? `Connected ${integration.type} integration.`}
            </p>

            <div className="mt-3 flex flex-wrap items-center gap-2">
              {integration.hint && (
                <div className="inline-flex items-center rounded-md border border-border dark:border-white/[0.06] bg-muted/50 dark:bg-black/20 px-2 py-0.5 font-mono text-[10px] text-muted-foreground dark:text-white/35">
                  {integration.type === "slack" ? "Webhook" : "Credential"} ••••{integration.hint}
                </div>
              )}

              {integration.backfillStatus && (
                <span className="inline-flex items-center rounded-md border border-blue-400/20 bg-blue-400/10 px-2 py-0.5 text-[10px] font-medium text-blue-300">
                  Backfill: {integration.backfillProgress != null ? `${integration.backfillProgress}%` : integration.backfillStatus}
                </span>
              )}
            </div>

            {isWorkflowProvider && (
              <SyncStatus integrationId={integration.id} />
            )}

            {isWorkflowProvider && provider?.capabilities?.workflowDiscovery && (
              <div className="mt-2 flex items-center gap-1.5 text-[11px] text-muted-foreground dark:text-white/35">
                <CheckCircle2 className="size-3 text-emerald-400/70" />
                {provider.capabilities.executionHistory
                  ? "Execution sync enabled"
                  : "Workflow discovery enabled"}
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="relative mt-auto border-t border-border dark:border-white/[0.065] bg-muted/20 dark:bg-white/[0.015] p-3.5">
          {isDeliveryChannel ? (
            <div className="grid grid-cols-3 gap-2">
              <Button
                variant="outline"
                className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                disabled={!isAdmin || testing}
                title={adminTitle}
                onClick={() => {
                  setTesting(true);
                  testMutation.mutate();
                }}
              >
                {testing && <Loader2 className="size-3 animate-spin mr-1" />}
                Test
              </Button>

              <Button
                variant="outline"
                className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                disabled={!isAdmin || sendingTest}
                title={adminTitle}
                onClick={() => {
                  setSendingTest(true);
                  sendTestMutation.mutate();
                }}
              >
                {sendingTest && <Loader2 className="size-3 animate-spin mr-1" />}
                Send
              </Button>

              <Button
                variant="outline"
                className="h-8 rounded-md border-red-400/10 bg-red-400/[0.025] text-xs text-red-300 hover:bg-red-400/[0.08] hover:text-red-200"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={() => onDisconnect(integration)}
              >
                Disconnect
              </Button>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-2">
              {provider?.capabilities?.workflowDiscovery && (
                <Button
                  variant="outline"
                  className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                  onClick={() => setWorkflowsOpen(true)}
                >
                  Workflows
                </Button>
              )}

              {provider?.capabilities?.executionHistory && (
                <Button
                  variant="outline"
                  className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                  disabled={!isAdmin || syncing}
                  title={adminTitle}
                  onClick={() => {
                    setSyncing(true);
                    syncMutation.mutate();
                  }}
                >
                  {syncing ? <Loader2 className="size-3 animate-spin mr-1" /> : <RefreshCw className="size-3 mr-1" />}
                  Sync
                </Button>
              )}

              <Button
                variant="outline"
                className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                disabled={!isAdmin || testing}
                title={adminTitle}
                onClick={() => {
                  setTesting(true);
                  testMutation.mutate();
                }}
              >
                {testing ? <Loader2 className="size-3 animate-spin mr-1" /> : <CheckCircle2 className="size-3 mr-1" />}
                Test
              </Button>

              <Button
                variant="outline"
                className="h-8 rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs hover:bg-muted dark:hover:bg-white/[0.07]"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={onReconnect}
              >
                Reconnect
              </Button>

              <Button
                variant="outline"
                className="col-span-2 h-8 rounded-md border-red-400/10 bg-red-400/[0.025] text-xs text-red-300 hover:bg-red-400/[0.08] hover:text-red-200"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={() => onDisconnect(integration)}
              >
                Disconnect
              </Button>
            </div>
          )}
        </div>
      </article>

      {workflowsOpen && isWorkflowProvider && provider?.capabilities?.workflowDiscovery && (
        <WorkflowDiscovery
          integrationId={integration.id}
          integrationName={integration.name}
          onClose={() => setWorkflowsOpen(false)}
        />
      )}
    </>
  );
}

interface CatalogProviderCardProps {
  provider: ProviderInfo;
  connectedCount: number;
  isAdmin: boolean;
  onConnect: () => void;
}

function CatalogProviderCard({
  provider,
  connectedCount,
  isAdmin,
  onConnect,
}: CatalogProviderCardProps) {
  const resolvedIcon = provider.icon ?? getProviderIcon(provider.type);
  const category = provider.category ?? getProviderCategory(provider.type);
  const docsUrl = provider.docsUrl || getProviderDocsUrl(provider.type);
  const adminTitle = isAdmin ? undefined : "Requires an admin";

  return (
    <article className="group relative flex h-full min-h-[240px] flex-col overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-sm dark:border-white/[0.065] dark:bg-[#0a0a0c] dark:text-white transition-colors duration-200 hover:border-border-strong hover:bg-muted/40 dark:hover:border-white/[0.12] dark:hover:bg-[#111114]">
      <div className="relative flex flex-1 flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="flex size-12 items-center justify-center rounded-lg border border-border bg-background dark:border-white/[0.08] dark:bg-white/[0.04]">
            <ProviderIcon
              provider={provider.type}
              name={provider.name}
              icon={resolvedIcon}
              className="size-6"
              theme="auto"
              fallbackInitials
            />
          </div>

          <div className="flex items-center gap-2">
            <span className="rounded-md border border-border bg-muted/50 dark:border-white/[0.07] dark:bg-white/[0.025] px-2 py-0.5 text-[10px] font-medium uppercase tracking-[0.08em] text-muted-foreground dark:text-white/45">
              {category.replaceAll("_", " ")}
            </span>

            {docsUrl && (
              <a
                href={docsUrl}
                target="_blank"
                rel="noreferrer"
                title="View API documentation & portal"
                className="inline-flex size-6 items-center justify-center rounded-md border border-border dark:border-white/[0.08] bg-muted/40 dark:bg-white/[0.03] text-muted-foreground dark:text-white/40 hover:text-foreground dark:hover:text-white transition-colors"
              >
                <ExternalLink className="size-3" />
              </a>
            )}

            {connectedCount > 0 ? (
              <span className="inline-flex items-center gap-1 rounded-md border border-emerald-400/20 bg-emerald-400/10 px-2 py-0.5 text-[11px] font-medium text-emerald-300">
                <span className="size-1.5 rounded-full bg-emerald-400" />
                {connectedCount} connected
              </span>
            ) : !provider.available ? (
              <span className="inline-flex items-center rounded-md border border-border bg-muted/40 dark:border-white/[0.08] dark:bg-white/[0.03] px-2 py-0.5 text-[11px] font-medium text-muted-foreground dark:text-white/45">
                Coming soon
              </span>
            ) : null}
          </div>
        </div>

        <div className="mt-4">
          <h3 title={provider.name} className="text-[15px] font-semibold tracking-[-0.01em] text-foreground dark:text-white/90">
            {provider.name}
          </h3>

          <p title={provider.description} className="mt-2 min-h-[38px] text-[13px] leading-5 text-muted-foreground dark:text-white/45 line-clamp-2">
            {provider.description}
          </p>
        </div>
      </div>

      <div className="relative mt-auto border-t border-border dark:border-white/[0.065] bg-muted/20 dark:bg-white/[0.015] p-3.5">
        <Button
          variant="outline"
          className="h-8 w-full rounded-md border-border dark:border-white/[0.08] bg-card dark:bg-white/[0.025] text-xs font-medium text-foreground dark:text-white/80 hover:bg-muted dark:hover:bg-white/[0.07] hover:text-foreground dark:hover:text-white"
          disabled={!isAdmin || !provider.available}
          title={adminTitle}
          onClick={onConnect}
        >
          {connectedCount > 0 ? (
            <>
              <Plus className="size-3.5 mr-1" />
              Add another instance
            </>
          ) : (
            <>
              <Plug className="size-3.5 mr-1" />
              Connect
              <ArrowUpRight className="ml-auto size-3 text-muted-foreground dark:text-white/40" />
            </>
          )}
        </Button>
      </div>
    </article>
  );
}

function DisconnectDialog({
  integration,
  onOpenChange,
}: {
  integration: Integration | null;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () => disconnectIntegration(integration!.id),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["integrations"],
      });
      toast.success("Integration disconnected.");
      onOpenChange(false);
    },
    onError: (err) =>
      toast.error(getErrorMessage(err, "Could not disconnect.")),
  });

  return (
    <Dialog open={!!integration} onOpenChange={onOpenChange}>
      <DialogContent className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0c]">
        <DialogHeader>
          <DialogTitle>
            Disconnect {integration?.name}?
          </DialogTitle>
          <DialogDescription>
            This permanently deletes the stored credentials for this connection instance.
            Any workflows or notifications monitoring this instance will stop.
          </DialogDescription>
        </DialogHeader>

        <DialogFooter>
          <Button
            type="button"
            variant="ghost"
            onClick={() => onOpenChange(false)}
          >
            Cancel
          </Button>

          <Button
            type="button"
            variant="destructive"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
          >
            {mutation.isPending && (
              <Loader2 className="size-4 animate-spin mr-1" />
            )}
            Disconnect
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function IntegrationsPage() {
  const role = useAuthStore((s) => s.currentRole);
  const isAdmin = hasRole(role, "ADMIN");

  const [connectOpen, setConnectOpen] = useState(false);
  const [connectProviderType, setConnectProviderType] = useState<string | null>(null);
  const [disconnectTarget, setDisconnectTarget] = useState<Integration | null>(null);

  const [search, setSearch] = useState("");
  const [selectedCategory, setSelectedCategory] = useState("All");

  const integrationsQuery = useQuery({
    queryKey: ["integrations"],
    queryFn: fetchIntegrations,
  });

  const providersQuery = useQuery({
    queryKey: ["integration-providers"],
    queryFn: fetchProviders,
  });

  const connectedIntegrations = useMemo(
    () =>
      (integrationsQuery.data?.integrations ?? []).filter(
        (i) => i.status === "connected" && !i.disabled,
      ),
    [integrationsQuery.data],
  );

  const allProviders = useMemo(
    () => providersQuery.data?.providers ?? [],
    [providersQuery.data?.providers],
  );

  const providerMap = useMemo(() => {
    const map = new Map<string, ProviderInfo>();
    for (const p of allProviders) {
      map.set(p.type.toLowerCase(), p);
    }
    return map;
  }, [allProviders]);

  const connectedCountsByType = useMemo(() => {
    const map = new Map<string, number>();
    for (const item of connectedIntegrations) {
      const type = item.type.toLowerCase();
      map.set(type, (map.get(type) ?? 0) + 1);
    }
    return map;
  }, [connectedIntegrations]);

  const filteredIntegrations = useMemo(() => {
    return connectedIntegrations.filter((item) => {
      const q = search.trim().toLowerCase();
      const provider = providerMap.get(item.type.toLowerCase());
      const category = provider?.category ?? getProviderCategory(item.type);

      const matchesSearch =
        !q ||
        item.name.toLowerCase().includes(q) ||
        item.type.toLowerCase().includes(q) ||
        (provider?.name.toLowerCase().includes(q) ?? false);

      const matchesCategory =
        selectedCategory === "All" || category === selectedCategory;

      return matchesSearch && matchesCategory;
    });
  }, [connectedIntegrations, providerMap, search, selectedCategory]);

  const filteredProviders = useMemo(() => {
    return allProviders.filter((p) => {
      const q = search.trim().toLowerCase();
      const category = p.category ?? getProviderCategory(p.type);

      const matchesSearch =
        !q ||
        p.name.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q);

      const matchesCategory =
        selectedCategory === "All" || category === selectedCategory;

      return matchesSearch && matchesCategory;
    });
  }, [allProviders, search, selectedCategory]);

  const isLoading = integrationsQuery.isPending || providersQuery.isPending;
  const isError = integrationsQuery.isError || providersQuery.isError;

  function openConnect(providerType: string) {
    setConnectProviderType(providerType);
    setConnectOpen(true);
  }

  function closeConnect(open: boolean) {
    setConnectOpen(open);
    if (!open) {
      setConnectProviderType(null);
    }
  }

  return (
    <PageContainer variant="wide">
      {/* Header */}
      <PageHeader
        eyebrow="Automation"
        title="Integrations"
        description="Connect automation platforms (n8n, Make, Zapier) and notification destinations."
      />

      {/* Search toolbar */}
      <Toolbar className="mb-4">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground dark:text-white/40" />

          <Input
            placeholder="Search integrations…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-10 text-sm"
          />
        </div>
      </Toolbar>

      {/* Category filters */}
      <div className="mb-6">
        <CategoryPillsRow
          categories={CATEGORIES}
          activeValue={selectedCategory}
          onSelect={setSelectedCategory}
          ariaLabel="Filter integrations by category"
        />
      </div>

      {isError && (
        <ErrorState
          title="Could not load integrations"
          message="Please check your connection and try again."
          onRetry={() => {
            void integrationsQuery.refetch();
            void providersQuery.refetch();
          }}
          className="mb-6"
        />
      )}

      {isLoading && !isError && (
        <CardGridSkeleton count={6} />
      )}

      {!isLoading && !isError && (
        <div className="space-y-10">
          {/* Section 1: Connected Instances */}
          {connectedIntegrations.length > 0 && (
            <section className="space-y-4">
              <div className="flex items-end justify-between gap-4">
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="text-sm font-semibold text-foreground dark:text-white/85">
                      Connected Instances
                    </h2>

                    <span className="rounded-md bg-emerald-500/10 px-2 py-0.5 text-[10px] font-medium text-emerald-600 dark:text-emerald-300">
                      {connectedIntegrations.length}
                    </span>
                  </div>

                  <p className="mt-1 text-xs text-muted-foreground dark:text-white/45">
                    Active connection instances across all platforms in this workspace.
                  </p>
                </div>
              </div>

              {filteredIntegrations.length === 0 ? (
                <EmptyState
                  icon={Search}
                  title="No matching connected instances"
                  description={`No active connections match “${search}”.`}
                  actions={
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setSearch("");
                        setSelectedCategory("All");
                      }}
                    >
                      Clear filters
                    </Button>
                  }
                />
              ) : (
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                  {filteredIntegrations.map((item) => (
                    <ConnectedInstanceCard
                      key={item.id}
                      integration={item}
                      provider={providerMap.get(item.type.toLowerCase())}
                      isAdmin={isAdmin}
                      onReconnect={() => openConnect(item.type)}
                      onDisconnect={setDisconnectTarget}
                    />
                  ))}
                </div>
              )}
            </section>
          )}

          {/* Section 2: Catalog */}
          <section className="space-y-4">
            <div className="flex items-end justify-between gap-4">
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-sm font-semibold text-foreground dark:text-white/85">
                    {connectedIntegrations.length > 0
                      ? "Integration Catalog"
                      : "Available Providers"}
                  </h2>

                  <span className="rounded-md bg-muted dark:bg-white/[0.05] px-2 py-0.5 text-[10px] font-medium text-muted-foreground dark:text-white/45">
                    {filteredProviders.length}
                  </span>
                </div>

                <p className="mt-1 text-xs text-muted-foreground dark:text-white/45">
                  Connect new automation platforms or add another instance to an existing connection.
                </p>
              </div>
            </div>

            {filteredProviders.length === 0 ? (
              <EmptyState
                icon={Search}
                title={
                  search || selectedCategory !== "All"
                    ? "No integrations found"
                    : "No available integrations"
                }
                description={
                  search
                    ? `Nothing matches “${search}”.`
                    : selectedCategory !== "All"
                      ? "No available integrations in this category."
                      : "No integrations available in the catalog."
                }
                actions={
                  search || selectedCategory !== "All" ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setSearch("");
                        setSelectedCategory("All");
                      }}
                    >
                      Clear filters
                    </Button>
                  ) : undefined
                }
              />
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                {filteredProviders.map((p) => (
                  <CatalogProviderCard
                    key={p.type}
                    provider={p}
                    connectedCount={connectedCountsByType.get(p.type.toLowerCase()) ?? 0}
                    isAdmin={isAdmin}
                    onConnect={() => openConnect(p.type)}
                  />
                ))}
              </div>
            )}
          </section>

          <div className="flex items-center justify-center gap-2 pb-3 text-[11px] text-muted-foreground dark:text-white/45">
            <CheckCircle2 className="size-3.5 text-emerald-400/80" />
            Connections are scoped per workspace and credentials are encrypted at rest
            <ChevronRight className="size-3" />
          </div>
        </div>
      )}

      <ConnectIntegrationDialog
        open={connectOpen}
        onOpenChange={closeConnect}
        providerType={connectProviderType}
      />

      <DisconnectDialog
        integration={disconnectTarget}
        onOpenChange={(open) => {
          if (!open) {
            setDisconnectTarget(null);
          }
        }}
      />
    </PageContainer>
  );
}