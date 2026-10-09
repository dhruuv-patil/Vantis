"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import {
  ArrowRight,
  Bot,
  CalendarDays,
  Globe2,
  Loader2,
  Mail,
  PanelsTopLeft,
  Search,
  Sparkles,
  UserCheck,
  Webhook,
} from "lucide-react";

import { toast } from "sonner";

import {
  fetchTemplates,
  getErrorMessage,
  useTemplate as createTemplate,
} from "@/lib/api";

import type { WorkflowTemplateSummary } from "@/types";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  CategoryPillsRow,
  ErrorState,
  LoadMoreButton,
  PageContainer,
  PageHeader,
  Toolbar,
} from "@/components/app/page-primitives";

import { cn } from "@/lib/utils";
import {
  BrandIcon,
  resolveTheSvgSlug,
  getBrandTitle,
  getBrandColor,
  THESVG_SLUGS,
} from "@/components/app/builder/node-visuals";

type TemplateApp = {
  key: string;
  name: string;
  color: string;
  iconSlug?: string;
  kind: "brand" | "generic";
};

const EMPTY_TEMPLATES: WorkflowTemplateSummary[] = [];

const TEMPLATE_APPS: Record<string, TemplateApp> = {
  ai: {
    key: "ai",
    name: "AI",
    color: "#8B5CF6",
    kind: "generic",
  },

  webhook: {
    key: "webhook",
    name: "Webhook",
    color: "#F59E0B",
    kind: "generic",
  },

  http: {
    key: "http",
    name: "HTTP",
    color: "#3B82F6",
    kind: "generic",
  },

  approval: {
    key: "approval",
    name: "Human Approval",
    color: "#10B981",
    kind: "generic",
  },

  email: {
    key: "email",
    name: "Email",
    color: "#EF4444",
    kind: "generic",
  },
};

function getTemplateApps(
  template: WorkflowTemplateSummary,
): TemplateApp[] {
  const providers = template.providers ?? [];

  return providers
    .map((provider) => {
      const normalized = provider
        .trim()
        .toLowerCase()
        .replace(/[\s_]+/g, "-");

      // Handle generic capabilities
      if (
        normalized === "ai" ||
        normalized === "llm" ||
        normalized === "agent" ||
        normalized === "aiagent"
      ) {
        return TEMPLATE_APPS.ai;
      }
      if (normalized === "webhook") {
        return TEMPLATE_APPS.webhook;
      }
      if (
        normalized === "http" ||
        normalized === "rest" ||
        normalized === "rest-api" ||
        normalized === "restapi"
      ) {
        return TEMPLATE_APPS.http;
      }
      if (
        normalized === "approval" ||
        normalized === "humanapproval" ||
        normalized === "human-approval"
      ) {
        return TEMPLATE_APPS.approval;
      }
      if (normalized === "email" || normalized === "smtp") {
        return TEMPLATE_APPS.email;
      }

      // Check predefined TEMPLATE_APPS
      if (TEMPLATE_APPS[normalized]) {
        return TEMPLATE_APPS[normalized];
      }

      // Resolve via the comprehensive brand rules registry
      const slug = resolveTheSvgSlug(normalized, provider);
      if (slug) {
        return {
          key: slug,
          name: getBrandTitle(slug, provider),
          color: getBrandColor(slug),
          iconSlug: slug,
          kind: "brand" as const,
        };
      }

      return null;
    })
    .filter((app): app is TemplateApp => app !== null);
}

export default function TemplatesPage() {
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("All");
  const [pageSize, setPageSize] = useState(24);

  const query = useQuery({
    queryKey: ["templates"],
    queryFn: fetchTemplates,
  });

  const all =
    query.data?.templates ?? EMPTY_TEMPLATES;

  const categories = useMemo(() => {
    const values = new Set<string>();

    for (const template of all) {
      if (template.category) {
        values.add(template.category);
      }
    }

    return [
      "All",
      ...Array.from(values).sort(),
    ];
  }, [all]);

  const filteredTemplates = useMemo(() => {
    const normalizedSearch =
      search.trim().toLowerCase();

    return all.filter((template) => {
      const matchesCategory =
        category === "All" ||
        template.category === category;

      if (!matchesCategory) {
        return false;
      }

      if (!normalizedSearch) {
        return true;
      }

      const searchable = [
        template.name,
        template.description,
        template.category,
        ...(template.tags ?? []),
        ...(template.providers ?? []),
      ]
        .join(" ")
        .toLowerCase();

      return searchable.includes(
        normalizedSearch,
      );
    });
  }, [all, category, search]);

  const recommended = filteredTemplates.slice(
    0,
    3,
  );

  const remaining = filteredTemplates.filter(
    (template) =>
      !recommended.some(
        (item) =>
          item.slug === template.slug,
      ),
  );

  const hasFilters =
    Boolean(search.trim()) ||
    category !== "All";

  const clearFilters = () => {
    setSearch("");
    setCategory("All");
  };

  return (
    <PageContainer variant="wide">
      <PageHeader
        eyebrow="Workflow library"
        title="Templates"
        description="Production-ready workflow blueprints for automation, reliability, operations, AI, and business processes."
      />

      <Toolbar className="mb-4">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground dark:text-white/40" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search templates…"
            className="pl-10 text-sm"
          />
        </div>
      </Toolbar>

      <div className="mb-8">
        <CategoryPillsRow
          categories={categories}
          activeValue={category}
          onSelect={setCategory}
          ariaLabel="Filter templates by category"
        />
      </div>

        {query.isLoading ? (
          <TemplateLoading />
        ) : query.isError ? (
          <ErrorState
            title="Could not load templates"
            message={getErrorMessage(
              query.error,
              "Something went wrong while loading the template library.",
            )}
            onRetry={() => void query.refetch()}
          />
        ) : filteredTemplates.length === 0 ? (
          <EmptyTemplates
            search={search}
            hasFilters={hasFilters}
            onClear={clearFilters}
          />
        ) : (
          <>
            {recommended.length > 0 && (
              <section className="mb-10">
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h2 className="text-sm font-semibold text-foreground dark:text-white/80">
                      Recommended
                    </h2>

                    <p className="mt-1 text-xs text-muted-foreground dark:text-white/50">
                      Start with a proven workflow
                      pattern.
                    </p>
                  </div>

                  <span className="text-[11px] text-muted-foreground dark:text-white/45">
                    {recommended.length} templates
                  </span>
                </div>

                <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
                  {recommended.map(
                    (template) => (
                      <TemplateCard
                        key={template.slug}
                        template={template}
                        featured
                      />
                    ),
                  )}
                </div>
              </section>
            )}

            {remaining.length > 0 && (
              <section>
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h2 className="text-sm font-semibold text-foreground dark:text-white/80">
                      All templates
                    </h2>

                    <p className="mt-1 text-xs text-muted-foreground dark:text-white/50">
                      Browse the complete Vantis
                      template library.
                    </p>
                  </div>

                  <span className="text-[11px] text-muted-foreground dark:text-white/45">
                    {remaining.length} templates
                  </span>
                </div>

                <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
                  {remaining.slice(0, pageSize).map(
                    (template) => (
                      <TemplateCard
                        key={template.slug}
                        template={template}
                      />
                    ),
                  )}
                </div>

                <LoadMoreButton
                  loadedCount={Math.min(pageSize, remaining.length)}
                  totalCount={remaining.length}
                  onLoadMore={() => setPageSize((prev) => prev + 24)}
                />
              </section>
            )}
          </>
        )}
    </PageContainer>
  );
}

function TemplateCard({
  template,
  featured = false,
}: {
  template: WorkflowTemplateSummary;
  featured?: boolean;
}) {
  const router = useRouter();
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: () =>
      createTemplate(template.slug),

    onSuccess: (workflow) => {
      queryClient.invalidateQueries({
        queryKey: ["workflows"],
      });

      toast.success(
        `Created "${workflow.name}" from a template.`,
      );

      router.push(
        `/workflows/${workflow.id}`,
      );
    },

    onError: (error) => {
      toast.error(
        getErrorMessage(
          error,
          "Could not create from this template.",
        ),
      );
    },
  });

  return (
    <article
      className={cn(
        "group overflow-hidden rounded-lg border bg-card dark:bg-[#0a0a0a]",
        "transition-colors duration-200",
        featured
          ? "border-primary/20 dark:border-white/[0.14] hover:border-primary/40 dark:hover:border-white/[0.22]"
          : "border-border dark:border-white/[0.075] hover:border-border/80 dark:hover:border-white/[0.15]",
      )}
    >
      <TemplatePreview
        template={template}
        featured={featured}
      />

      <div className="flex min-h-[190px] flex-col p-4">
        <div>
          <h3
            title={template.name}
            className="line-clamp-2 text-[15px] font-semibold leading-5 text-foreground group-hover:text-foreground dark:text-white/90 dark:group-hover:text-white"
          >
            {template.name}
          </h3>

          <p className="mt-1 text-[11px] font-medium uppercase tracking-[0.10em] text-muted-foreground dark:text-white/45">
            {template.category}
          </p>
        </div>

        <p
          title={template.description}
          className="mt-3 line-clamp-3 text-xs leading-5 text-muted-foreground dark:text-white/50"
        >
          {template.description}
        </p>

        <div className="mt-auto flex items-center justify-between gap-3 pt-4">
          <div className="flex min-w-0 flex-wrap gap-1.5">
            {(template.tags ?? [])
              .slice(0, 2)
              .map((tag) => (
                <span
                  key={tag}
                  className="rounded-md border border-border dark:border-white/[0.07] bg-muted/40 dark:bg-transparent px-2 py-1 text-[11px] text-muted-foreground dark:text-white/45"
                >
                  {tag}
                </span>
              ))}
          </div>

          <Button
            size="sm"
            onClick={() =>
              mutation.mutate()
            }
            disabled={mutation.isPending}
            className="h-8 shrink-0 rounded-md bg-foreground text-background hover:bg-foreground/90 dark:bg-white dark:text-black dark:hover:bg-white/90 px-3 text-xs font-medium"
          >
            {mutation.isPending ? (
              <Loader2 className="mr-1.5 size-3.5 animate-spin" />
            ) : (
              <ArrowRight className="mr-1.5 size-3.5" />
            )}

            {mutation.isPending
              ? "Creating..."
              : "Use template"}
          </Button>
        </div>
      </div>
    </article>
  );
}
function TemplatePreview({
  template,
  featured,
}: {
  template: WorkflowTemplateSummary;
  featured: boolean;
}) {
  const apps = getTemplateApps(template);

  const visibleApps = apps.slice(0, 3);

  const extraCount = Math.max(
    0,
    apps.length - visibleApps.length,
  );

  return (
    <div className="relative h-[178px] overflow-hidden border-b border-border dark:border-white/[0.07] bg-muted/30 dark:bg-[#080808]">
      {/* BACKGROUND */}

      <div
        className="absolute inset-0 opacity-[0.25] text-muted-foreground dark:opacity-[0.14] dark:text-white"
        style={{
          backgroundImage:
            "radial-gradient(circle, currentColor 1px, transparent 1px)",
          backgroundSize: "18px 18px",
        }}
      />

      {/* FEATURED BADGE */}

      {featured && (
        <div className="absolute left-3 top-3 z-20 flex items-center gap-1.5 rounded-md border border-border dark:border-white/[0.10] bg-card/90 dark:bg-[#0a0a0a]/90 px-2.5 py-1.5 text-[10px] font-medium text-foreground dark:text-white/70 backdrop-blur shadow-sm">
          <Sparkles className="size-3" />
          Top recommendation
        </div>
      )}

      {/* APP TRAY */}

      <div className="absolute inset-0 flex items-center justify-center px-5">
        <div className="flex items-center rounded-xl border border-border bg-card dark:border-white/[0.12] dark:bg-[#121215] px-3 py-3 shadow-[0_18px_45px_rgba(0,0,0,0.18)] dark:shadow-[0_18px_45px_rgba(0,0,0,0.48)]">
          {visibleApps.length > 0 ? (
            <>
              {visibleApps.map(
                (app, index) => (
                  <div
                    key={`${app.key}-${index}`}
                    className="flex items-center"
                  >
                    <LandingIntegrationIcon
                      app={app}
                    />

                    {index <
                      visibleApps.length - 1 && (
                      <ArrowRight className="mx-2 size-4 text-muted-foreground" />
                    )}
                  </div>
                ),
              )}

              {extraCount > 0 && (
                <>
                  <ArrowRight className="mx-2 size-4 text-muted-foreground" />

                  <div className="flex size-10 items-center justify-center rounded-lg border border-border bg-muted text-sm font-semibold text-muted-foreground">
                    +{extraCount}
                  </div>
                </>
              )}
            </>
          ) : (
            <>
              <LandingIntegrationIcon
                app={TEMPLATE_APPS.webhook}
              />

              <ArrowRight className="mx-2 size-4 text-slate-500" />

              <LandingIntegrationIcon
                app={TEMPLATE_APPS.http}
              />
            </>
          )}
        </div>
      </div>

      {/* STEP COUNT */}

      <div className="absolute bottom-3 left-3 rounded-md border border-border dark:border-white/[0.09] bg-card/90 dark:bg-[#0a0a0a]/90 px-2.5 py-1 text-[10px] text-muted-foreground dark:text-white/40 backdrop-blur shadow-sm">
        {template.nodeCount} steps
      </div>
    </div>
  );
}

/* ==========================================================================
   INTEGRATION ICON
   ========================================================================== */

function LandingIntegrationIcon({
  app,
}: {
  app: TemplateApp;
}) {
  return (
    <div
      className="flex size-10 items-center justify-center rounded-lg border border-border bg-card dark:border-white/[0.12] dark:bg-white/[0.04] shadow-sm"
      title={app.name}
    >
      <BrandIcon
        slug={app.iconSlug}
        type={app.key}
        label={app.name}
        name={app.name}
        className="size-[22px]"
        theme="auto"
        alt={app.name}
        fallbackColor={app.color}
      />
    </div>
  );
}

/* ==========================================================================
   TEMPLATE PROVIDER → APP RESOLUTION
   ========================================================================== */



/* ==========================================================================
   EMPTY TEMPLATES
   ========================================================================== */

function EmptyTemplates({
  search,
  hasFilters,
  onClear,
}: {
  search: string;
  hasFilters: boolean;
  onClear: () => void;
}) {
  return (
    <div className="rounded-lg border border-dashed border-border dark:border-white/[0.08] bg-muted/20 dark:bg-white/[0.012] px-5 py-16 text-center">
      <div className="mx-auto flex size-11 items-center justify-center rounded-lg border border-border dark:border-white/[0.07] bg-card dark:bg-white/[0.025] text-muted-foreground dark:text-white/40">
        <PanelsTopLeft className="size-5" />
      </div>

      <h3 className="mt-4 text-sm font-medium text-foreground dark:text-white/80">
        No templates found
      </h3>

      <p className="mx-auto mt-1 max-w-sm text-xs leading-5 text-muted-foreground dark:text-white/50">
        {search
          ? `Nothing matches "${search}". Try a different search or clear the filters.`
          : "There are no templates available yet."}
      </p>

      {hasFilters && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onClear}
          className="mt-5 h-8 rounded-md text-xs"
        >
          Clear filters
        </Button>
      )}
    </div>
  );
}

/* ==========================================================================
   LOADING
   ========================================================================== */

function TemplateLoading() {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
      {Array.from({
        length: 6,
      }).map((_, index) => (
        <Skeleton
          key={index}
          className="h-[368px] rounded-lg"
        />
      ))}
    </div>
  );
}