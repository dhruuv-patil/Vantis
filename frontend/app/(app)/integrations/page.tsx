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
  Loader2,
  Plug,
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
import { initialsFrom } from "@/lib/utils";

import {
  getProviderCategory,
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
  } = q.data;

  const failed = status === "FAILED";

  const label = failed
    ? "Sync failed"
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
          ? "border-red-400/20 bg-red-400/10 text-red-300"
          : "border-white/[0.07] bg-white/[0.025] text-white/35"
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

interface ProviderCardProps {
  provider: ProviderInfo;
  connected: Integration | null;
  isAdmin: boolean;
  onConnect: () => void;
  onDisconnect: (
    integration: Integration,
  ) => void;
}

function ProviderCard({
  provider,
  connected,
  isAdmin,
  onConnect,
  onDisconnect,
}: ProviderCardProps) {
  const [
    workflowsOpen,
    setWorkflowsOpen,
  ] = useState(false);

  const [
    testing,
    setTesting,
  ] = useState(false);

  const [
    sendingTest,
    setSendingTest,
  ] = useState(false);

  const [
    syncing,
    setSyncing,
  ] = useState(false);

  const queryClient =
    useQueryClient();

  const isDeliveryChannel =
    provider.capabilities.webhooks;

  const isWorkflowProvider =
    !isDeliveryChannel;

  const resolvedIcon =
    provider.icon ??
    getProviderIcon(provider.type);

  const category =
    provider.category ??
    getProviderCategory(
      provider.type,
    );

  const testMutation =
    useMutation({
      mutationFn: () =>
        testIntegration(
          connected!.id,
        ),

      onSuccess: (result) =>
        toast[
          result.success
            ? "success"
            : "error"
        ](
          result.message ??
            (result.success
              ? "Connection is healthy."
              : "Connection test failed."),
        ),

      onError: (err) =>
        toast.error(
          getErrorMessage(
            err,
            "Test failed.",
          ),
        ),

      onSettled: () =>
        setTesting(false),
    });

  const sendTestMutation =
    useMutation({
      mutationFn: () =>
        sendTestIntegration(
          connected!.id,
        ),

      onSuccess: (result) =>
        toast[
          result.success
            ? "success"
            : "error"
        ](
          result.message ??
            (result.success
              ? "Test event sent."
              : "Test event failed."),
        ),

      onError: (err) =>
        toast.error(
          getErrorMessage(
            err,
            "Test send failed.",
          ),
        ),

      onSettled: () =>
        setSendingTest(false),
    });

  const syncMutation =
    useMutation({
      mutationFn: () =>
        syncIntegration(
          connected!.id,
        ),

      onSuccess: (result) => {
        toast.success(
          `Synced ${result.workflows} workflows, ${result.executions} executions, ${result.events} events`,
        );
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(
            err,
            "Sync failed.",
          ),
        ),

      onSettled: () => {
        setSyncing(false);

        queryClient.invalidateQueries({
          queryKey: ["integrations"],
        });

        queryClient.invalidateQueries({
          queryKey: [
            "integrations",
            connected!.id,
            "sync",
          ],
        });
      },
    });

  const syncQuery = useQuery({
    queryKey: ["integrations", connected?.id, "sync"],
    queryFn: () => fetchSyncStatus(connected!.id),
    enabled: !!connected && isWorkflowProvider,
    refetchInterval: 60_000,
  });

  const needsAttention = syncQuery.data?.status === "FAILED";

  const adminTitle = isAdmin
    ? undefined
    : "Requires an admin";

  return (
    <>
      <article
        className="
          group relative flex h-full min-h-[270px] flex-col
          overflow-hidden rounded-xl
          border border-white/[0.065]
          bg-[#0a0a0c]
          transition-colors duration-200
          hover:border-white/[0.12]
          hover:bg-[#111114]
        "
      >
        {/* Subtle background detail */}
        <div className="pointer-events-none absolute -right-16 -top-16 size-36 rounded-full bg-white/[0.02] blur-2xl transition-opacity group-hover:opacity-100" />

        <div className="relative flex flex-1 flex-col p-5">
          <div className="flex items-start justify-between gap-4">
            <div className="flex size-12 items-center justify-center rounded-lg border border-white/[0.08] bg-white/[0.04]">
              <ProviderIcon
                provider={provider.type}
                name={provider.name}
                icon={provider.icon || resolvedIcon}
                className="size-6"
                theme="dark"
                fallbackInitials
              />
            </div>

            <div className="flex items-center gap-2">
              <span className="rounded-md border border-white/[0.07] bg-white/[0.025] px-2.5 py-1 text-[10px] font-medium uppercase tracking-[0.08em] text-white/45">
                {category.replaceAll(
                  "_",
                  " ",
                )}
              </span>

              {needsAttention ? (
                <span className="inline-flex items-center gap-1.5 rounded-md border border-amber-400/20 bg-amber-400/10 px-2.5 py-1 text-[11px] font-medium text-amber-300">
                  <span className="size-1.5 rounded-full bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.7)]" />
                  Needs attention
                </span>
              ) : connected ? (
                <span className="inline-flex items-center gap-1.5 rounded-md border border-emerald-400/20 bg-emerald-400/10 px-2.5 py-1 text-[11px] font-medium text-emerald-300">
                  <span className="size-1.5 rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.7)]" />
                  Connected
                </span>
              ) : !provider.available ? (
                <span className="inline-flex items-center rounded-md border border-white/[0.08] bg-white/[0.03] px-2.5 py-1 text-[11px] font-medium text-white/45">
                  Coming soon
                </span>
              ) : null}
            </div>
          </div>

          <div className="mt-5">
            <h3
              title={provider.name}
              className="text-[15px] font-semibold tracking-[-0.01em] text-white/90"
            >
              {provider.name}
            </h3>

            <p
              title={provider.description}
              className="mt-2 min-h-[42px] text-[13px] leading-5 text-white/45"
            >
              {provider.description}
            </p>

            {connected?.hint && (
              <div className="mt-3 inline-flex items-center rounded-md border border-white/[0.06] bg-black/20 px-2 py-1 font-mono text-[10px] text-white/35">
                {provider.type ===
                "slack"
                  ? "Webhook"
                  : "Credential"}{" "}
                ••••{connected.hint}
              </div>
            )}

            {connected &&
              isWorkflowProvider && (
                <SyncStatus
                  integrationId={
                    connected.id
                  }
                />
              )}

            {connected &&
              isWorkflowProvider &&
              provider.capabilities
                ?.workflowDiscovery && (
                <div className="mt-2 flex items-center gap-1.5 text-[11px] text-white/35">
                  <CheckCircle2 className="size-3 text-emerald-400/70" />

                  {provider
                    .capabilities
                    .executionHistory
                    ? "Execution sync enabled"
                    : "Workflow discovery enabled"}
                </div>
              )}
          </div>
        </div>

        {/* Footer */}
        <div className="relative mt-auto border-t border-white/[0.065] bg-white/[0.015] p-4">
          {!connected ? (
            <Button
              variant="outline"
              className="h-9 w-full rounded-md border-white/[0.08] bg-white/[0.025] text-xs font-medium text-white/80 hover:bg-white/[0.07] hover:text-white"
              disabled={!isAdmin}
              title={adminTitle}
              onClick={onConnect}
            >
              <Plug className="size-4" />

              Connect

              <ArrowUpRight className="ml-auto size-3.5 text-white/40" />
            </Button>
          ) : isDeliveryChannel ? (
            <div className="grid grid-cols-3 gap-2">
              <Button
                variant="outline"
                className="h-9 rounded-md border-white/[0.08] bg-white/[0.025] text-xs hover:bg-white/[0.07]"
                disabled={
                  !isAdmin ||
                  testing
                }
                title={adminTitle}
                onClick={() => {
                  setTesting(true);
                  testMutation.mutate();
                }}
              >
                {testing && (
                  <Loader2 className="size-3.5 animate-spin" />
                )}

                Test
              </Button>

              <Button
                variant="outline"
                className="h-9 rounded-md border-white/[0.08] bg-white/[0.025] text-xs hover:bg-white/[0.07]"
                disabled={
                  !isAdmin ||
                  sendingTest
                }
                title={adminTitle}
                onClick={() => {
                  setSendingTest(true);
                  sendTestMutation.mutate();
                }}
              >
                {sendingTest && (
                  <Loader2 className="size-3.5 animate-spin" />
                )}

                Send test
              </Button>

              <Button
                variant="outline"
                className="h-9 rounded-md border-red-400/10 bg-red-400/[0.025] text-xs text-red-300 hover:bg-red-400/[0.08] hover:text-red-200"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={() =>
                  onDisconnect(
                    connected,
                  )
                }
              >
                Disconnect
              </Button>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-2">
              {provider.capabilities
                ?.workflowDiscovery && (
                <Button
                  variant="outline"
                  className="h-9 rounded-md border-white/[0.08] bg-white/[0.025] text-xs hover:bg-white/[0.07]"
                  onClick={() =>
                    setWorkflowsOpen(
                      true,
                    )
                  }
                >
                  Workflows
                </Button>
              )}

              {provider.capabilities
                ?.executionHistory && (
                <Button
                  variant="outline"
                  className="h-9 rounded-md border-white/[0.08] bg-white/[0.025] text-xs hover:bg-white/[0.07]"
                  disabled={
                    !isAdmin ||
                    syncing
                  }
                  title={adminTitle}
                  onClick={() => {
                    setSyncing(true);
                    syncMutation.mutate();
                  }}
                >
                  {syncing ? (
                    <Loader2 className="size-3.5 animate-spin" />
                  ) : (
                    <RefreshCw className="size-3.5" />
                  )}

                  Sync
                </Button>
              )}

              <Button
                variant="outline"
                className="h-9 rounded-md border-white/[0.08] bg-white/[0.025] text-xs hover:bg-white/[0.07]"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={onConnect}
              >
                Reconnect
              </Button>

              <Button
                variant="outline"
                className="h-9 rounded-md border-red-400/10 bg-red-400/[0.025] text-xs text-red-300 hover:bg-red-400/[0.08] hover:text-red-200"
                disabled={!isAdmin}
                title={adminTitle}
                onClick={() =>
                  onDisconnect(
                    connected,
                  )
                }
              >
                Disconnect
              </Button>
            </div>
          )}
        </div>
      </article>

      {workflowsOpen &&
        connected &&
        isWorkflowProvider &&
        provider.capabilities
          ?.workflowDiscovery && (
          <WorkflowDiscovery
            integrationId={
              connected.id
            }
            integrationName={
              connected.name
            }
            onClose={() =>
              setWorkflowsOpen(
                false,
              )
            }
          />
        )}
    </>
  );
}

function DisconnectDialog({
  integration,
  onOpenChange,
}: {
  integration: Integration | null;
  onOpenChange: (
    open: boolean,
  ) => void;
}) {
  const queryClient =
    useQueryClient();

  const mutation =
    useMutation({
      mutationFn: () =>
        disconnectIntegration(
          integration!.id,
        ),

      onSuccess: () => {
        queryClient.invalidateQueries({
          queryKey: [
            "integrations",
          ],
        });

        toast.success(
          "Integration disconnected.",
        );

        onOpenChange(false);
      },

      onError: (err) =>
        toast.error(
          getErrorMessage(
            err,
            "Could not disconnect.",
          ),
        ),
    });

  return (
    <Dialog
      open={!!integration}
      onOpenChange={onOpenChange}
    >
      <DialogContent className="border-white/[0.08] bg-[#0a0a0c]">
        <DialogHeader>
          <DialogTitle>
            Disconnect{" "}
            {integration?.name}?
          </DialogTitle>

          <DialogDescription>
            This permanently deletes
            the stored credential.
            Any workflow
            notifications using
            this connection will
            stop until you reconnect
            it.
          </DialogDescription>
        </DialogHeader>

        <DialogFooter>
          <Button
            type="button"
            variant="ghost"
            onClick={() =>
              onOpenChange(false)
            }
          >
            Cancel
          </Button>

          <Button
            type="button"
            variant="destructive"
            onClick={() =>
              mutation.mutate()
            }
            disabled={
              mutation.isPending
            }
          >
            {mutation.isPending && (
              <Loader2 className="size-4 animate-spin" />
            )}

            Disconnect
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function IntegrationsPage() {
  const role = useAuthStore(
    (s) => s.currentRole,
  );

  const isAdmin = hasRole(
    role,
    "ADMIN",
  );

  const [
    connectOpen,
    setConnectOpen,
  ] = useState(false);

  const [
    connectProviderType,
    setConnectProviderType,
  ] = useState<
    string | null
  >(null);

  const [
    disconnectTarget,
    setDisconnectTarget,
  ] = useState<
    Integration | null
  >(null);

  const [search, setSearch] =
    useState("");

  const [
    selectedCategory,
    setSelectedCategory,
  ] = useState("All");

  const integrationsQuery =
    useQuery({
      queryKey: [
        "integrations",
      ],
      queryFn:
        fetchIntegrations,
    });

  const providersQuery =
    useQuery({
      queryKey: [
        "integration-providers",
      ],
      queryFn: fetchProviders,
    });

  const connectedByType =
    useMemo(
      () =>
        new Map<
          string,
          Integration
        >(
          (
            integrationsQuery
              .data
              ?.integrations ?? []
          )
            .filter(
              (i) =>
                i.status ===
                "connected",
            )
            .map(
              (i) =>
                [
                  i.type,
                  i,
                ] as const,
            ),
        ),
      [
        integrationsQuery.data,
      ],
    );

  const allProviders = useMemo(
    () => providersQuery.data?.providers ?? [],
    [providersQuery.data?.providers],
  );

  const filteredProviders =
    useMemo(() => {
      return allProviders.filter(
        (p) => {
          const q = search
            .trim()
            .toLowerCase();

          const matchesSearch =
            !q ||
            p.name
              .toLowerCase()
              .includes(q) ||
            p.description
              .toLowerCase()
              .includes(q);

          const category =
            p.category ??
            getProviderCategory(
              p.type,
            );

          const matchesCategory =
            selectedCategory ===
              "All" ||
            category ===
              selectedCategory;

          return (
            matchesSearch &&
            matchesCategory
          );
        },
      );
    }, [
      allProviders,
      search,
      selectedCategory,
    ]);

  const connectedProviders =
    useMemo(
      () =>
        filteredProviders.filter(
          (p) =>
            connectedByType.has(
              p.type,
            ),
        ),
      [
        filteredProviders,
        connectedByType,
      ],
    );

  const availableProviders =
    useMemo(
      () =>
        filteredProviders.filter(
          (p) =>
            !connectedByType.has(
              p.type,
            ),
        ),
      [
        filteredProviders,
        connectedByType,
      ],
    );

  const isLoading =
    integrationsQuery.isPending ||
    providersQuery.isPending;

  const isError =
    integrationsQuery.isError ||
    providersQuery.isError;

  function openConnect(
    providerType: string,
  ) {
    setConnectProviderType(
      providerType,
    );

    setConnectOpen(true);
  }

  function closeConnect(
    open: boolean,
  ) {
    setConnectOpen(open);

    if (!open) {
      setConnectProviderType(
        null,
      );
    }
  }

  return (
    <PageContainer variant="wide">
      {/* Header */}
      <PageHeader
        eyebrow="Automation"
        title="Integrations"
        description="Connect the services your workflows monitor and deliver to."
      />

      {/* Search toolbar */}
      <Toolbar className="mb-4">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-white/40" />

          <Input
            placeholder="Search integrations…"
            value={search}
            onChange={(e) =>
              setSearch(
                e.target.value,
              )
            }
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

        {!isLoading &&
          !isError && (
            <div className="space-y-10">
              {connectedProviders.length >
                0 && (
                <section className="space-y-4">
                  <div className="flex items-end justify-between gap-4">
                    <div>
                      <div className="flex items-center gap-2">
                        <h2 className="text-sm font-semibold text-white/85">
                          Connected
                        </h2>

                        <span className="rounded-md bg-emerald-400/10 px-2 py-0.5 text-[10px] font-medium text-emerald-300">
                          {
                            connectedProviders.length
                          }
                        </span>
                      </div>

                      <p className="mt-1 text-xs text-white/45">
                        Active connections
                        in this workspace.
                      </p>
                    </div>
                  </div>

                  <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                    {connectedProviders.map(
                      (p) => (
                        <ProviderCard
                          key={p.type}
                          provider={p}
                          connected={
                            connectedByType.get(
                              p.type,
                            ) ?? null
                          }
                          isAdmin={
                            isAdmin
                          }
                          onConnect={() =>
                            openConnect(
                              p.type,
                            )
                          }
                          onDisconnect={
                            setDisconnectTarget
                          }
                        />
                      ),
                    )}
                  </div>
                </section>
              )}

              <section className="space-y-4">
                <div className="flex items-end justify-between gap-4">
                  <div>
                    <div className="flex items-center gap-2">
                      <h2 className="text-sm font-semibold text-white/85">
                        {connectedProviders.length
                          ? "Available"
                          : "Integration catalog"}
                      </h2>

                      <span className="rounded-md bg-white/[0.05] px-2 py-0.5 text-[10px] font-medium text-white/45">
                        {
                          availableProviders.length
                        }
                      </span>
                    </div>

                    <p className="mt-1 text-xs text-white/45">
                      Connect a service
                      and make it
                      available to your
                      workflows.
                    </p>
                  </div>
                </div>

                {availableProviders.length === 0 ? (
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
                          : "Everything in this category is already connected."
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
                    {availableProviders.map(
                      (p) => (
                        <ProviderCard
                          key={p.type}
                          provider={p}
                          connected={null}
                          isAdmin={
                            isAdmin
                          }
                          onConnect={() =>
                            openConnect(
                              p.type,
                            )
                          }
                          onDisconnect={
                            setDisconnectTarget
                          }
                        />
                      ),
                    )}
                  </div>
                )}
              </section>

              <div className="flex items-center justify-center gap-2 pb-3 text-[11px] text-white/45">
                <CheckCircle2 className="size-3.5" />

                Connections are
                managed per workspace

                <ChevronRight className="size-3" />
              </div>
            </div>
          )}

      <ConnectIntegrationDialog
        open={connectOpen}
        onOpenChange={closeConnect}
        providerType={
          connectProviderType
        }
      />

      <DisconnectDialog
        integration={
          disconnectTarget
        }
        onOpenChange={(open) => {
          if (!open) {
            setDisconnectTarget(
              null,
            );
          }
        }}
      />
    </PageContainer>
  );
}