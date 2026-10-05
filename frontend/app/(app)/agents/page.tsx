"use client";

import Link from "next/link";
import { Suspense, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Bot, Plus, Search } from "lucide-react";

import { fetchAiAgents, getErrorMessage } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { CreateAgentDialog } from "@/components/app/create-agent-dialog";
import {
  EmptyState,
  ErrorState,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  TableSkeleton,
  Toolbar,
} from "@/components/app/page-primitives";

/**
 * AI Agents library. Lists the current org's saved agents; each row opens the
 * editor + test console. Search is client-side.
 */
function AgentsPageContent() {
  const [search, setSearch] = useState("");
  const [pageSize, setPageSize] = useState(25);
  const [createOpen, setCreateOpen] = useState(false);

  // The ⌘K palette's "New AI agent" action deep-links here with ?new=1.
  const params = useSearchParams();
  useEffect(() => {
    if (params.get("new") === "1") setCreateOpen(true);
  }, [params]);

  const query = useQuery({
    queryKey: ["ai-agents"],
    queryFn: fetchAiAgents,
  });

  const agents = useMemo(() => {
    const all = query.data?.agents ?? [];
    const term = search.trim().toLowerCase();
    if (!term) return all;
    return all.filter(
      (a) =>
        a.name.toLowerCase().includes(term) ||
        (a.description ?? "").toLowerCase().includes(term),
    );
  }, [query.data, search]);

  return (
    <PageContainer variant="wide">
      <PageHeader
        eyebrow="Automation"
        title="AI Agents"
        description="Reusable AI agents you can test here or drop into a workflow."
        actions={
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            <span>New agent</span>
          </Button>
        }
      />

      <Toolbar className="mb-6">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-white/40" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search agents…"
            className="pl-10 text-sm"
          />
        </div>
      </Toolbar>

      {query.isPending ? (
        <TableSkeleton rows={4} cols={3} />
      ) : query.isError ? (
        <ErrorState
          title="Could not load agents"
          message={getErrorMessage(
            query.error,
            "Something went wrong while loading the AI agent library.",
          )}
          onRetry={() => query.refetch()}
        />
      ) : agents.length === 0 ? (
        <EmptyState
          icon={Bot}
          title={search ? "No agents match your search" : "No AI agents yet"}
          description={
            search
              ? "Try adjusting your search terms to find what you're looking for."
              : "Create an agent with custom instructions and tools to automate steps in your workflows."
          }
          actions={
            search ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setSearch("")}
              >
                Clear search
              </Button>
            ) : (
              <Link href="/workflows">
                <Button variant="outline" size="sm">
                  Browse workflows
                </Button>
              </Link>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-white/[0.065] bg-[#0a0a0c]">
            <ul className="divide-y divide-white/[0.05]">
              {agents.slice(0, pageSize).map((a) => (
                <li key={a.id}>
                  <Link
                    href={`/agents/${a.id}`}
                    className="flex items-center gap-4 px-4 py-3.5 transition-colors duration-150 hover:bg-white/[0.04]"
                  >
                    <span className="flex size-9 shrink-0 items-center justify-center rounded-lg border border-white/[0.08] bg-white/[0.025] text-white/60">
                      <Bot className="size-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span
                          title={a.name}
                          className="truncate font-medium text-white/90"
                        >
                          {a.name}
                        </span>
                        {a.model && (
                          <Badge
                            variant="outline"
                            className="font-mono text-[10px] text-white/50"
                          >
                            {a.model}
                          </Badge>
                        )}
                      </span>
                      <span
                        title={a.description || undefined}
                        className="block truncate text-xs text-white/45"
                      >
                        {a.description || "No description provided"}
                      </span>
                    </span>
                    <span className="hidden shrink-0 text-right text-xs text-white/40 sm:block">
                      {a.tools.length} {a.tools.length === 1 ? "tool" : "tools"}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          <LoadMoreButton
            loadedCount={Math.min(pageSize, agents.length)}
            totalCount={agents.length}
            onLoadMore={() => setPageSize((prev) => prev + 25)}
          />
        </>
      )}

      <CreateAgentDialog open={createOpen} onOpenChange={setCreateOpen} />
    </PageContainer>
  );
}

export default function AgentsPage() {
  return (
    <Suspense fallback={null}>
      <AgentsPageContent />
    </Suspense>
  );
}
