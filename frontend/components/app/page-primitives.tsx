"use client";

import Link from "next/link";
import { useEffect, type ReactNode } from "react";
import { AlertCircle, ArrowLeft, RefreshCw } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  getStatusConfig,
  type StatusTone,
} from "@/lib/status";

/* -------------------------------------------------------------------------- */
/* PageContainer                                                              */
/* -------------------------------------------------------------------------- */

export type PageContainerVariant = "wide" | "narrow";

export function PageContainer({
  variant = "wide",
  children,
  className,
}: {
  variant?: PageContainerVariant;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "mx-auto w-full px-4 py-6 sm:px-6 sm:py-8 lg:px-8",
        variant === "narrow" ? "max-w-4xl" : "max-w-[1500px]",
        className,
      )}
    >
      {children}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* PageHeader                                                                 */
/* -------------------------------------------------------------------------- */

export interface PageHeaderBackLink {
  href: string;
  label?: string;
}

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  backLink,
  className,
  metaTitle,
}: {
  eyebrow?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  backLink?: PageHeaderBackLink;
  className?: string;
  metaTitle?: string;
}) {
  useEffect(() => {
    const raw = metaTitle ?? (typeof title === "string" ? title : undefined);
    if (raw && typeof document !== "undefined") {
      document.title = `${raw} · Vantis`;
    }
  }, [metaTitle, title]);

  return (
    <div className={cn("mb-8", className)}>
      {backLink && (
        <div className="mb-3">
          <Link
            href={backLink.href}
            className="inline-flex items-center gap-1.5 text-xs text-white/50 transition-colors hover:text-white"
          >
            <ArrowLeft className="size-3.5" />
            <span>{backLink.label ?? "Back"}</span>
          </Link>
        </div>
      )}

      <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          {eyebrow && (
            <p className="mb-2 font-mono text-[10px] font-semibold uppercase tracking-[0.18em] text-white/40">
              {eyebrow}
            </p>
          )}

          <h1 className="text-2xl font-semibold tracking-tight text-white">
            {title}
          </h1>

          {description && (
            <p className="mt-1.5 max-w-2xl text-sm leading-6 text-white/50">
              {description}
            </p>
          )}
        </div>

        {actions && (
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {actions}
          </div>
        )}
      </div>
    </div>
  );
}

/** Backward compatibility alias */
export const AppPageHeader = PageHeader;

/* -------------------------------------------------------------------------- */
/* Status Tokens (Badge and Dot)                                              */
/* -------------------------------------------------------------------------- */

export function StatusDot({
  status,
  tone,
  className,
}: {
  status?: string | null;
  tone?: StatusTone;
  className?: string;
}) {
  const config = getStatusConfig(tone ?? status);
  return (
    <span
      aria-hidden="true"
      className={cn("size-1.5 shrink-0 rounded-full", config.dotClass, className)}
    />
  );
}

export function StatusBadge({
  status,
  tone,
  label,
  className,
}: {
  status?: string | null;
  tone?: StatusTone;
  label?: ReactNode;
  className?: string;
}) {
  const config = getStatusConfig(tone ?? status);
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-[11px] font-medium leading-4 tracking-tight",
        config.badgeClass,
        className,
      )}
    >
      <StatusDot status={status} tone={tone} />
      <span>{label ?? config.label}</span>
    </span>
  );
}

/* -------------------------------------------------------------------------- */
/* KpiStrip / MetricStrip                                                     */
/* -------------------------------------------------------------------------- */

export type MetricItem = {
  label: string;
  value: ReactNode;
  detail?: string;
  tone?: StatusTone;
};

export function getKpiItemClasses(index: number, total: number) {
  if (total === 5) {
    const isCol1Sm = index === 0 || index === 2;
    const isLastSm = index === 4;
    const hasBottomBorderSm = index < 4;
    const hasRightBorderLg = index < 4;

    return cn(
      "min-h-[92px] p-4 flex flex-col justify-between",
      // Base mobile (1 col)
      index < total - 1 ? "border-b border-white/[0.05]" : "border-b-0",
      // sm (2 cols: 640px to 1024px)
      isCol1Sm ? "sm:border-r sm:border-white/[0.05]" : "sm:border-r-0",
      hasBottomBorderSm ? "sm:border-b sm:border-white/[0.05]" : "sm:border-b-0",
      isLastSm ? "sm:col-span-2 lg:col-span-1" : "sm:col-span-1",
      // lg (5 cols: >= 1024px)
      "lg:border-b-0",
      hasRightBorderLg ? "lg:border-r lg:border-white/[0.05]" : "lg:border-r-0",
    );
  }

  if (total === 4) {
    const isCol1Sm = index % 2 === 0;
    const hasBottomBorderSm = index < 2;
    const hasRightBorderLg = index < 3;

    return cn(
      "min-h-[92px] p-4 flex flex-col justify-between",
      // Base mobile (1 col)
      index < total - 1 ? "border-b border-white/[0.05]" : "border-b-0",
      // sm (2 cols: 640px to 1024px)
      isCol1Sm ? "sm:border-r sm:border-white/[0.05]" : "sm:border-r-0",
      hasBottomBorderSm ? "sm:border-b sm:border-white/[0.05]" : "sm:border-b-0",
      // lg (4 cols: >= 1024px)
      "lg:border-b-0",
      hasRightBorderLg ? "lg:border-r lg:border-white/[0.05]" : "lg:border-r-0",
    );
  }

  // Fallback for 3 or arbitrary count
  return cn(
    "min-h-[92px] p-4 flex flex-col justify-between",
    index < total - 1 ? "border-b border-white/[0.05]" : "border-b-0",
    index < total - 1 ? "sm:border-r sm:border-white/[0.05] sm:border-b-0" : "sm:border-r-0 sm:border-b-0",
  );
}

export function KpiStrip({
  items,
  className,
}: {
  items: MetricItem[];
  className?: string;
}) {
  const defaultGridClass =
    items.length === 5
      ? "sm:grid-cols-2 lg:grid-cols-5"
      : items.length === 3
        ? "sm:grid-cols-3"
        : "sm:grid-cols-2 lg:grid-cols-4";

  return (
    <dl
      className={cn(
        "grid overflow-hidden rounded-xl border border-white/[0.065] bg-[#0a0a0c]",
        defaultGridClass,
        className,
      )}
    >
      {items.map((item, index) => {
        const hasTone = Boolean(item.tone && item.tone !== "neutral");

        return (
          <div
            key={item.label}
            className={getKpiItemClasses(index, items.length)}
          >
            <div>
              <dt className="text-[10px] font-medium uppercase tracking-[0.10em] text-white/40">
                {item.label}
              </dt>
              <dd className="mt-2 text-2xl font-semibold leading-none tracking-tight tabular-nums text-white/90">
                {item.value}
              </dd>
            </div>
            {item.detail && (
              <div className="mt-2 flex items-center gap-1.5 text-[11px] text-white/45">
                {hasTone && <StatusDot tone={item.tone} />}
                <span className="truncate">{item.detail}</span>
              </div>
            )}
          </div>
        );
      })}
    </dl>
  );
}

/** Backward compatibility alias */
export const MetricStrip = KpiStrip;

/* -------------------------------------------------------------------------- */
/* PageToolbar                                                                */
/* -------------------------------------------------------------------------- */

export function Toolbar({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-2.5 sm:flex-row sm:items-center",
        className,
      )}
    >
      {children}
    </div>
  );
}

/** Backward compatibility alias */
export const PageToolbar = Toolbar;

/* -------------------------------------------------------------------------- */
/* CategoryPillsRow                                                           */
/* -------------------------------------------------------------------------- */

export interface CategoryPillsRowProps {
  categories: readonly { label: string; value: string; count?: number }[] | readonly string[];
  activeValue: string;
  onSelect: (value: string) => void;
  className?: string;
  ariaLabel?: string;
}

export function CategoryPillsRow({
  categories,
  activeValue,
  onSelect,
  className,
  ariaLabel = "Filter by category",
}: CategoryPillsRowProps) {
  return (
    <div className={cn("relative w-full overflow-hidden", className)}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        className="flex gap-2 overflow-x-auto pb-1.5 pt-0.5 scroll-smooth snap-x snap-mandatory scrollbar-none [mask-image:linear-gradient(to_right,black_0%,black_calc(100%-2.5rem),transparent_100%)] sm:[mask-image:linear-gradient(to_right,black_0%,black_calc(100%-3.5rem),transparent_100%)]"
      >
        {categories.map((cat) => {
          const value = typeof cat === "string" ? cat : cat.value;
          const label = typeof cat === "string" ? cat : cat.label;
          const count = typeof cat === "string" ? undefined : cat.count;
          const isActive = activeValue === value;

          return (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => onSelect(value)}
              className={cn(
                "snap-start shrink-0 rounded-lg border px-3.5 py-1.5 text-xs font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-white/30",
                isActive
                  ? "border-white bg-white text-black font-semibold shadow-sm"
                  : "border-white/[0.08] bg-white/[0.025] text-white/50 hover:border-white/[0.14] hover:bg-white/[0.05] hover:text-white/80",
              )}
            >
              <span>{label}</span>
              {count !== undefined && (
                <span
                  className={cn(
                    "ml-1.5 text-[10px] tabular-nums",
                    isActive ? "text-black/60" : "text-white/35",
                  )}
                >
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* AppPanel                                                                   */
/* -------------------------------------------------------------------------- */

export function AppPanel({
  title,
  description,
  actions,
  children,
  className,
}: {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={cn(
        "overflow-hidden rounded-xl border border-white/[0.065] bg-[#0a0a0c]",
        className,
      )}
    >
      {(title || description || actions) && (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-white/[0.05] px-4 py-3.5">
          <div>
            {title && (
              <h2 className="text-sm font-medium text-white/85">{title}</h2>
            )}
            {description && (
              <p className="mt-1 text-xs text-white/40">{description}</p>
            )}
          </div>
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

/* -------------------------------------------------------------------------- */
/* EmptyState & ErrorState                                                    */
/* -------------------------------------------------------------------------- */

export function EmptyState({
  icon: Icon,
  title,
  description,
  actions,
  className,
}: {
  icon?: React.ComponentType<{ className?: string }>;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border border-dashed border-white/[0.08] bg-[#0a0a0c]/60 px-6 py-14 text-center",
        className,
      )}
    >
      {Icon && (
        <div className="flex size-10 items-center justify-center rounded-lg border border-white/[0.08] bg-white/[0.025] text-white/40">
          <Icon className="size-5" />
        </div>
      )}
      <h3 className={cn("text-sm font-medium text-white/90", Icon && "mt-3")}>
        {title}
      </h3>
      {description && (
        <p className="mt-1.5 max-w-sm text-xs leading-5 text-white/45">
          {description}
        </p>
      )}
      {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}
    </section>
  );
}

export function ErrorState({
  title = "Something went wrong",
  message,
  onRetry,
  className,
}: {
  title?: ReactNode;
  message?: ReactNode;
  onRetry?: () => void;
  className?: string;
}) {
  return (
    <section
      role="alert"
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border border-red-500/20 bg-red-500/[0.04] px-6 py-12 text-center",
        className,
      )}
    >
      <div className="flex size-9 items-center justify-center rounded-lg border border-red-500/30 bg-red-500/10 text-red-400">
        <AlertCircle className="size-4" />
      </div>
      <h3 className="mt-3 text-sm font-medium text-red-300">{title}</h3>
      {message && (
        <p className="mx-auto mt-1.5 max-w-md text-xs leading-5 text-white/50">
          {message}
        </p>
      )}
      {onRetry && (
        <Button
          variant="outline"
          size="sm"
          onClick={onRetry}
          className="mt-4 gap-2 border-red-500/20 text-white/80 hover:bg-red-500/10"
        >
          <RefreshCw className="size-3.5" />
          <span>Try again</span>
        </Button>
      )}
    </section>
  );
}

/** Backward compatibility alias */
export function AppStateSurface({
  kind,
  title,
  description,
  action,
  className,
}: {
  kind: "loading" | "empty" | "error";
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  if (kind === "error") {
    return (
      <ErrorState
        title={title}
        message={description}
        className={className}
      />
    );
  }

  return (
    <section
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border px-6 py-14 text-center",
        "border-dashed border-white/[0.08] bg-[#0a0a0c]/60",
        className,
      )}
    >
      <h2 className="text-sm font-medium text-white/85">{title}</h2>
      {description && (
        <p className="mt-1.5 max-w-md text-xs leading-5 text-white/45">
          {description}
        </p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </section>
  );
}

/* -------------------------------------------------------------------------- */
/* Skeletons                                                                  */
/* -------------------------------------------------------------------------- */

export function TableSkeleton({
  rows = 5,
  cols = 4,
  className,
}: {
  rows?: number;
  cols?: number;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-white/[0.065] bg-[#0a0a0c]",
        className,
      )}
    >
      <div className="border-b border-white/[0.05] bg-white/[0.02] p-3.5">
        <Skeleton className="h-4 w-48 rounded bg-white/[0.05]" />
      </div>
      <div className="divide-y divide-white/[0.04]">
        {Array.from({ length: rows }).map((_, r) => (
          <div key={r} className="flex items-center gap-4 p-4">
            {Array.from({ length: cols }).map((_, c) => (
              <Skeleton
                key={c}
                className={cn(
                  "h-4 rounded bg-white/[0.04]",
                  c === 0 ? "w-1/4" : c === cols - 1 ? "w-16" : "w-1/3",
                )}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

export function CardGridSkeleton({
  count = 6,
  columns = 3,
  className,
}: {
  count?: number;
  columns?: 2 | 3 | 4;
  className?: string;
}) {
  const colClass =
    columns === 2
      ? "grid-cols-1 sm:grid-cols-2"
      : columns === 4
        ? "grid-cols-1 sm:grid-cols-2 lg:grid-cols-4"
        : "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3";

  return (
    <div className={cn("grid gap-4", colClass, className)}>
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className="rounded-xl border border-white/[0.065] bg-[#0a0a0c] p-5"
        >
          <div className="flex items-center justify-between">
            <Skeleton className="h-4 w-28 rounded bg-white/[0.05]" />
            <Skeleton className="size-5 rounded-full bg-white/[0.05]" />
          </div>
          <Skeleton className="mt-3 h-3 w-3/4 rounded bg-white/[0.03]" />
          <Skeleton className="mt-2 h-3 w-1/2 rounded bg-white/[0.03]" />
          <div className="mt-6 flex items-center justify-between">
            <Skeleton className="h-5 w-16 rounded bg-white/[0.04]" />
            <Skeleton className="h-3 w-20 rounded bg-white/[0.04]" />
          </div>
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Pagination / Load More                                                     */
/* -------------------------------------------------------------------------- */

export function LoadMoreButton({
  loadedCount,
  totalCount,
  onLoadMore,
  className,
}: {
  loadedCount: number;
  totalCount: number;
  onLoadMore: () => void;
  className?: string;
}) {
  const remaining = Math.max(0, totalCount - loadedCount);
  if (remaining <= 0) return null;

  return (
    <div className={cn("mt-6 flex flex-col items-center justify-center gap-2", className)}>
      <Button
        variant="outline"
        size="sm"
        onClick={onLoadMore}
        className="gap-2 text-xs"
      >
        <span>Load more</span>
        <span className="font-mono text-[11px] text-white/45">
          ({remaining} remaining)
        </span>
      </Button>
      <span className="text-[11px] text-white/40">
        Showing {loadedCount} of {totalCount} items
      </span>
    </div>
  );
}
