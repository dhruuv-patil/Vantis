"use client";

import * as React from "react";
import {
  ChevronDown,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Search,
  X,
  Zap,
} from "lucide-react";

import { cn } from "@/lib/utils";
import type { NodeDefinition } from "@/types";
import { Button } from "@/components/ui/button";
import {
  DND_MIME,
  groupByCategory,
} from "@/components/app/builder/shared";
import {
  NodeIcon,
  resolveAccent,
} from "@/components/app/builder/node-visuals";

interface NodePaletteProps {
  defs: NodeDefinition[];
  collapsed: boolean;
  onToggle: () => void;
  /** Click-to-add fallback (drops the node at the canvas center). */
  onAdd: (type: string) => void;
  isLoading?: boolean;
  isError?: boolean;
}

export function NodePalette({
  defs,
  collapsed,
  onToggle,
  onAdd,
  isLoading,
  isError,
}: NodePaletteProps) {
  const [search, setSearch] = React.useState("");
  const searchInputRef = React.useRef<HTMLInputElement>(null);

  const searching = search.trim().length > 0;

  const groups = React.useMemo(() => {
    const term = search.trim().toLowerCase();

    const filtered = !term
      ? defs
      : defs.filter(
          (def) =>
            def.label.toLowerCase().includes(term) ||
            def.type.toLowerCase().includes(term) ||
            def.category.toLowerCase().includes(term) ||
            def.description?.toLowerCase().includes(term),
        );

    return groupByCategory(filtered);
  }, [defs, search]);

  const visibleCount = React.useMemo(
    () => groups.reduce((sum, g) => sum + g.defs.length, 0),
    [groups],
  );

  // "/" focuses search (when not already typing somewhere)
  React.useEffect(() => {
    if (collapsed) return;

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "/" || e.metaKey || e.ctrlKey || e.altKey) return;

      const el = e.target as HTMLElement | null;
      const typing =
        el?.tagName === "INPUT" ||
        el?.tagName === "TEXTAREA" ||
        el?.isContentEditable;

      if (typing) return;

      e.preventDefault();
      searchInputRef.current?.focus();
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [collapsed]);

  if (collapsed) {
    return (
      <div className="flex h-full w-12 shrink-0 flex-col items-center border-r border-white/[0.075] bg-[#090909] py-3">
        <Button
          variant="ghost"
          size="icon"
          onClick={onToggle}
          aria-label="Expand node palette"
          title="Expand node palette"
          className="size-8 text-white/45 hover:bg-white/[0.06] hover:text-white"
        >
          <PanelLeftOpen className="size-4" />
        </Button>
      </div>
    );
  }

  return (
    <aside className="flex h-full w-[280px] shrink-0 flex-col border-r border-white/[0.075] bg-[#090909]">
      {/* ================================================================
          HEADER
          ================================================================ */}

      <div className="flex h-12 shrink-0 items-center justify-between border-b border-white/[0.075] px-3.5">
        <div className="flex min-w-0 items-center gap-2">
          {/* <div className="flex size-6 shrink-0 items-center justify-center rounded-md bg-white/[0.06]">
            <span className="size-1.5 rounded-full bg-brand-400 shadow-[0_0_8px_rgba(99,102,241,0.75)]" />
          </div> */}

          <span className="text-[13px] font-semibold tracking-tight text-white/90">
            NODES
          </span>

          <span className="rounded-full bg-white/[0.06] px-1.5 py-0.5 text-[9px] font-medium tabular-nums text-white/35">
            {searching ? visibleCount : defs.length}
          </span>
        </div>

        <Button
          variant="ghost"
          size="icon"
          onClick={onToggle}
          aria-label="Collapse node palette"
          title="Collapse node palette"
          className="size-8 text-white/35 hover:bg-white/[0.06] hover:text-white"
        >
          <PanelLeftClose className="size-4" />
        </Button>
      </div>

      {/* ================================================================
          SEARCH (always visible)
          ================================================================ */}

      <div className="shrink-0 border-b border-white/[0.075] px-3 py-2.5">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-white/40" />

          <input
            ref={searchInputRef}
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                setSearch("");
                e.currentTarget.blur();
              }
            }}
            placeholder="Search nodes"
            aria-label="Search nodes"
            className={cn(
              "h-9 w-full rounded-lg",
              "border border-white/[0.08] bg-white/[0.025]",
              "pl-8 pr-8 text-xs text-white/85 outline-none",
              "placeholder:text-white/45",
              "transition-colors",
              "focus:border-brand-400/30 focus:bg-white/[0.04]",
            )}
          />

          {searching ? (
            <button
              type="button"
              onClick={() => {
                setSearch("");
                searchInputRef.current?.focus();
              }}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 flex size-5 -translate-y-1/2 items-center justify-center rounded text-white/40 transition-colors hover:bg-white/[0.06] hover:text-white"
            >
              <X className="size-3" />
            </button>
          ) : (
            <kbd className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 rounded border border-white/[0.08] px-1.5 py-px font-mono text-[10px] text-white/45">
              /
            </kbd>
          )}
        </div>
      </div>

      {/* ================================================================
          NODE LIST
          ================================================================ */}

      <div className="min-h-0 flex-1 overflow-y-auto px-2.5 pb-3 pt-1">
        {isLoading ? (
          <LoadingState />
        ) : isError ? (
          <p className="px-2 py-4 text-xs text-red-300/80">
            Could not load node types.
          </p>
        ) : groups.length === 0 ? (
          <EmptyState search={search} />
        ) : (
          <div className="space-y-4">
            {groups.map((group) => (
              <PaletteGroup
                key={group.category}
                label={group.category}
                defs={group.defs}
                forceOpen={searching}
                onAdd={onAdd}
              />
            ))}
          </div>
        )}
      </div>

      {/* ================================================================
          FOOTER
          ================================================================ */}

      <div className="shrink-0 border-t border-white/[0.075] px-3 py-2.5">
        <p className="text-[11px] leading-4 text-white/45">
          Drag a node onto the canvas
          <span className="mx-1 text-white/20">·</span>
          Click to add
        </p>
      </div>
    </aside>
  );
}

function PaletteGroup({
  label,
  defs,
  forceOpen,
  onAdd,
}: {
  label: string;
  defs: NodeDefinition[];
  forceOpen: boolean;
  onAdd: (type: string) => void;
}) {
  const [open, setOpen] = React.useState(true);
  const isOpen = open || forceOpen;
  const accent = resolveAccent(label);

  return (
    <section>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={isOpen}
        className={cn(
          "sticky top-0 z-10 -mx-2.5 mb-1 flex w-[calc(100%+20px)] items-center gap-1.5 px-3.5 py-2",
          "bg-[#090909]/95 backdrop-blur",
          "text-left text-[10px] font-semibold uppercase tracking-[0.16em]",
          "text-white/45 transition-colors hover:text-white/75",
        )}
      >
        <ChevronDown
          className={cn(
            "size-3 transition-transform duration-150",
            !isOpen && "-rotate-90",
          )}
        />

        <span
          className="size-1.5 rounded-full"
          style={{ background: `rgb(${accent} / 0.8)` }}
        />

        <span>{formatCategory(label)}</span>

        <span className="ml-auto text-[10px] font-normal tabular-nums text-white/45">
          {defs.length}
        </span>
      </button>

      {isOpen && (
        <div className="space-y-0.5">
          {defs.map((def) => (
            <PaletteItem key={def.type} def={def} onAdd={onAdd} />
          ))}
        </div>
      )}
    </section>
  );
}

function PaletteItem({
  def,
  onAdd,
}: {
  def: NodeDefinition;
  onAdd: (type: string) => void;
}) {
  const accent = resolveAccent(def.category, def.trigger);

  return (
    <button
      type="button"
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData(DND_MIME, def.type);
        e.dataTransfer.effectAllowed = "move";
      }}
      onClick={() => onAdd(def.type)}
      title={def.description}
      className={cn(
        "group flex w-full items-center gap-2.5",
        "rounded-lg border border-transparent px-2 py-2",
        "cursor-grab text-left",
        "transition-all duration-150",
        "hover:border-zinc-200 hover:bg-zinc-100 dark:hover:border-white/[0.08] dark:hover:bg-white/[0.035]",
        "active:cursor-grabbing",
      )}
    >
      <NodeIcon
        variant="palette"
        type={def.type}
        label={def.label}
        icon={def.icon}
        accent={accent}
        theme="auto"
      />

      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5">
          <span className="truncate text-[12px] font-medium text-zinc-800 transition-colors group-hover:text-black dark:text-white/80 dark:group-hover:text-white">
            {def.label}
          </span>

          {def.trigger && (
            <Zap
              className="size-3 shrink-0 fill-amber-400 text-amber-400"
              aria-label="Trigger"
            />
          )}
        </span>

        {def.description && (
          <span className="mt-0.5 block truncate text-[11px] leading-4 text-zinc-500 dark:text-white/45">
            {def.description}
          </span>
        )}
      </span>

      <Plus className="size-3.5 shrink-0 text-transparent transition-colors group-hover:text-zinc-400 dark:group-hover:text-white/40" />
    </button>
  );
}

function LoadingState() {
  return (
    <div className="space-y-4 px-1 pt-3">
      {[1, 2, 3].map((group) => (
        <div key={group} className="space-y-2">
          <div className="h-2.5 w-20 animate-pulse rounded bg-white/[0.05]" />

          {[1, 2, 3].map((item) => (
            <div
              key={item}
              className="h-[52px] animate-pulse rounded-lg bg-white/[0.025]"
            />
          ))}
        </div>
      ))}
    </div>
  );
}

function EmptyState({ search }: { search: string }) {
  return (
    <div className="flex flex-col items-center justify-center px-4 py-12 text-center">
      <div className="mb-3 flex size-9 items-center justify-center rounded-lg border border-white/[0.07] bg-white/[0.025]">
        <Search className="size-4 text-white/40" />
      </div>

      <p className="text-xs font-medium text-white/70">
        {search ? "No nodes found" : "No nodes available"}
      </p>

      {search && (
        <p className="mt-1 text-[11px] text-white/45">
          Try a different search.
        </p>
      )}
    </div>
  );
}

function formatCategory(category: string) {
  return category.replace(/[_-]/g, " ");
}