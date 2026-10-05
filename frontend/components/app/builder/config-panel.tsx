"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  Braces,
  Info,
  PanelRightClose,
  Settings2,
  Sparkles,
  Variable,
} from "lucide-react";

import { cn } from "@/lib/utils";
import { fetchAiAgents, fetchIntegrations } from "@/lib/api";
import type { ConfigField, NodeDefinition } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { resolveIcon, type FlowNode } from "@/components/app/builder/shared";
import { BrandIcon, resolveTheSvgSlug } from "@/components/app/builder/node-visuals";

interface ConfigPanelProps {
  node: FlowNode | null;
  def: NodeDefinition | undefined;

  /** Node ids reachable upstream — offered as {{nodeId.field}} references. */
  upstreamIds: string[];

  onClose: () => void;
  onLabelChange: (label: string) => void;
  onConfigChange: (key: string, value: unknown) => void;
}

function NodeBrandIcon({
  type,
  label,
  icon,
  className,
}: {
  type?: string;
  label?: string;
  icon?: string;
  className?: string;
}) {
  return (
    <BrandIcon
      type={type}
      label={label}
      icon={icon}
      className={cn("size-5", className)}
      theme="auto"
    />
  );
}

const fieldControlClass =
  "h-9 w-full rounded-lg border-white/[0.09] bg-white/[0.035] text-white shadow-none transition-all placeholder:text-white/45 hover:border-white/[0.14] focus-visible:border-primary/50 focus-visible:ring-2 focus-visible:ring-primary/10";

const textAreaClass =
  "w-full rounded-lg border-white/[0.09] bg-white/[0.035] text-white shadow-none transition-all placeholder:text-white/45 hover:border-white/[0.14] focus-visible:border-primary/50 focus-visible:ring-2 focus-visible:ring-primary/10";

export function ConfigPanel({
  node,
  def,
  upstreamIds,
  onClose,
  onLabelChange,
  onConfigChange,
}: ConfigPanelProps) {
  const panelClassName =
    "flex h-full w-[320px] max-w-[320px] min-w-0 shrink-0 flex-col overflow-hidden border-l border-white/[0.07] bg-[#08090b] shadow-[-18px_0_40px_-30px_rgba(0,0,0,0.9)]";

  if (!node) {
    return (
      <aside className={panelClassName} aria-label="Inspector">
        <div className="flex h-14 shrink-0 items-center border-b border-white/[0.07] px-4">
          <div className="flex min-w-0 items-center gap-2.5">
            <div className="flex size-8 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.035]">
              <Settings2 className="size-4 text-white/55" />
            </div>

            <div className="min-w-0">
              <p className="text-[13px] font-semibold tracking-[-0.01em] text-white/90">
                Inspector
              </p>

              <p className="text-[11px] text-white/45">
                Workflow node settings
              </p>
            </div>
          </div>
        </div>

        <div className="flex min-h-0 flex-1 items-center justify-center p-7">
          <div className="max-w-[230px] text-center">
            <div className="mx-auto mb-4 flex size-12 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.025] shadow-[0_0_30px_-18px_rgba(255,255,255,0.35)]">
              <Settings2 className="size-5 text-white/40" />
            </div>

            <p className="text-sm font-medium text-white/80">
              Select a node
            </p>

            <p className="mt-1.5 text-xs leading-relaxed text-white/45">
              Choose a node on the canvas to edit its configuration and inputs.
            </p>
          </div>
        </div>
      </aside>
    );
  }

  const config = node.data.config ?? {};
  const title = def?.label ?? node.type;
  const brandSlug = resolveTheSvgSlug(node.type, title);

  return (
    <aside className={panelClassName} aria-label="Inspector">
      <div className="shrink-0 border-b border-white/[0.07] bg-gradient-to-b from-white/[0.02] to-transparent">
        <div className="flex h-14 items-center gap-2.5 px-3.5">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-xl border border-white/[0.09] bg-white/[0.04] shadow-[0_8px_24px_-18px_rgba(255,255,255,0.45)]">
            <NodeBrandIcon
              type={node.type}
              label={title}
              icon={def?.icon}
            />
          </div>

          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] font-semibold tracking-[-0.01em] text-white/90">
              {title}
            </p>

            <div className="mt-0.5 flex min-w-0 items-center gap-1.5">
              <span className="truncate font-mono text-[10px] uppercase tracking-[0.11em] text-white/45">
                {node.type}
              </span>

              {brandSlug && (
                <span
                  className="size-1 rounded-full bg-primary/70"
                  aria-hidden="true"
                />
              )}
            </div>
          </div>

          <Button
            variant="ghost"
            size="icon"
            className="size-8 shrink-0 rounded-lg text-white/40 hover:bg-white/[0.06] hover:text-white/80"
            onClick={onClose}
            aria-label="Close inspector"
          >
            <PanelRightClose className="size-4" />
          </Button>
        </div>
      </div>

      <div className="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto">
        <div className="space-y-3.5 p-3.5">
          {def?.description && (
            <div className="rounded-xl border border-white/[0.07] bg-white/[0.022] p-3">
              <div className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.13em] text-white/45">
                <Info className="size-3" />
                About this node
              </div>

              <p className="break-words text-[11px] leading-relaxed text-white/60">
                {def.description}
              </p>
            </div>
          )}

          <section className="rounded-xl border border-white/[0.07] bg-white/[0.018] p-3">
            <div className="mb-3 flex items-center gap-2">
              <div className="flex size-6 items-center justify-center rounded-lg bg-primary/[0.12] text-primary">
                <Settings2 className="size-3.5" />
              </div>

              <div>
                <p className="text-[11px] font-semibold text-white/80">
                  General
                </p>

                <p className="text-[11px] text-white/45">
                  Identity and display
                </p>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label
                htmlFor="node-label"
                className="text-[10px] font-medium uppercase tracking-[0.1em] text-white/40"
              >
                Label
              </Label>

              <Input
                id="node-label"
                value={node.data.label ?? ""}
                placeholder={def?.label ?? "Node label"}
                className={fieldControlClass}
                onChange={(e) => onLabelChange(e.target.value)}
              />
            </div>
          </section>

          {def && def.configFields.length > 0 && (
            <section className="rounded-xl border border-white/[0.07] bg-white/[0.018] p-3">
              <div className="mb-3 flex items-center gap-2">
                <div className="flex size-6 items-center justify-center rounded-lg bg-white/[0.05] text-white/55">
                  <Braces className="size-3.5" />
                </div>

                <div>
                  <p className="text-[11px] font-semibold text-white/80">
                    Configuration
                  </p>

                  <p className="text-[11px] text-white/45">
                    Runtime inputs for this node
                  </p>
                </div>
              </div>

              <div className="space-y-3.5">
                {def.configFields.map((field) => (
                  <FieldControl
                    key={field.key}
                    field={field}
                    value={config[field.key]}
                    onChange={(v) => onConfigChange(field.key, v)}
                  />
                ))}
              </div>
            </section>
          )}

          <VariableHint upstreamIds={upstreamIds} />
        </div>
      </div>

      <div className="shrink-0 border-t border-white/[0.07] bg-[#08090b]/95 px-3.5 py-2.5 backdrop-blur">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-white/45">
            Node ID
          </span>

          <span className="min-w-0 truncate font-mono text-[11px] text-white/50">
            {node.id}
          </span>
        </div>
      </div>
    </aside>
  );
}

function FieldControl({
  field,
  value,
  onChange,
}: {
  field: ConfigField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const controlId = `cfg-${field.key}`;

  return (
    <div className="min-w-0 space-y-1.5">
      {field.type !== "boolean" && (
        <Label
          htmlFor={controlId}
          className="flex min-w-0 items-center gap-1 text-[11px] font-medium uppercase tracking-[0.08em] text-white/60"
        >
          <span className="min-w-0 truncate">
            {field.label}
          </span>

          {field.required && (
            <span className="text-primary">
              *
            </span>
          )}
        </Label>
      )}

      <FieldWidget
        controlId={controlId}
        field={field}
        value={value}
        onChange={onChange}
      />

      {field.help && (
        <p className="break-words text-[11px] leading-relaxed text-white/45">
          {field.help}
        </p>
      )}
    </div>
  );
}

function FieldWidget({
  controlId,
  field,
  value,
  onChange,
}: {
  controlId: string;
  field: ConfigField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  switch (field.type) {
    case "text":
    case "code":
      return (
        <Textarea
          id={controlId}
          value={typeof value === "string" ? value : ""}
          placeholder={field.placeholder ?? undefined}
          rows={field.type === "code" ? 6 : 3}
          className={cn(
            textAreaClass,
            "resize-y",
            field.type === "code" &&
              "font-mono text-xs leading-relaxed",
          )}
          onChange={(e) => onChange(e.target.value)}
        />
      );

    case "number":
      return (
        <Input
          id={controlId}
          type="number"
          value={
            typeof value === "number" || typeof value === "string"
              ? String(value)
              : ""
          }
          placeholder={field.placeholder ?? undefined}
          className={fieldControlClass}
          onChange={(e) =>
            onChange(
              e.target.value === ""
                ? null
                : Number(e.target.value),
            )
          }
        />
      );

    case "boolean":
      return (
        <label
          htmlFor={controlId}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-white/[0.07] bg-white/[0.025] px-3 py-2.5 transition-colors hover:border-white/[0.12] hover:bg-white/[0.035]"
        >
          <div className="min-w-0 pr-3">
            <span className="block break-words text-xs font-medium text-white/75">
              {field.label}
            </span>

            {field.help && (
              <span className="mt-0.5 block break-words text-[11px] text-white/45">
                {field.help}
              </span>
            )}
          </div>

          <input
            id={controlId}
            type="checkbox"
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
            className="size-4 shrink-0 cursor-pointer accent-primary"
          />
        </label>
      );

    case "select":
      return (
        <select
          id={controlId}
          value={typeof value === "string" ? value : ""}
          onChange={(e) =>
            onChange(e.target.value || null)
          }
          className={cn(
            fieldControlClass,
            "px-3 py-1 text-sm outline-none",
          )}
        >
          <option value="">— Select —</option>

          {field.options.map((opt) => (
            <option key={opt} value={opt}>
              {opt}
            </option>
          ))}
        </select>
      );

    case "json":
      return (
        <JsonField
          controlId={controlId}
          value={value}
          placeholder={field.placeholder}
          onChange={onChange}
        />
      );

    case "agent":
      return (
        <AgentSelect
          controlId={controlId}
          value={value}
          onChange={onChange}
        />
      );

    case "integration":
      return (
        <IntegrationSelect
          controlId={controlId}
          value={value}
          onChange={onChange}
        />
      );

    case "string":
    default:
      return (
        <Input
          id={controlId}
          value={typeof value === "string" ? value : ""}
          placeholder={field.placeholder ?? undefined}
          className={fieldControlClass}
          onChange={(e) => onChange(e.target.value)}
        />
      );
  }
}

function AgentSelect({
  controlId,
  value,
  onChange,
}: {
  controlId: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const query = useQuery({
    queryKey: ["ai-agents"],
    queryFn: fetchAiAgents,
  });

  const agents = query.data?.agents ?? [];
  const current =
    typeof value === "string"
      ? value
      : "";

  if (query.isError) {
    return (
      <p className="rounded-lg border border-destructive/20 bg-destructive/5 p-2.5 text-[10px] leading-relaxed text-destructive">
        Could not load agents. The inline instructions below will be used.
      </p>
    );
  }

  if (!query.isPending && agents.length === 0) {
    return (
      <div className="rounded-lg border border-white/[0.07] bg-white/[0.02] p-3">
        <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold text-white/60">
          <Sparkles className="size-3" />
          No saved agents
        </div>

        <p className="text-[11px] leading-relaxed text-white/45">
          <Link
            href="/agents"
            className="text-primary underline-offset-2 hover:underline"
          >
            Create one
          </Link>{" "}
          or use the inline instructions below.
        </p>
      </div>
    );
  }

  return (
    <select
      id={controlId}
      value={current}
      disabled={query.isPending}
      onChange={(e) =>
        onChange(e.target.value || null)
      }
      className={cn(
        fieldControlClass,
        "px-3 py-1 text-sm outline-none",
      )}
    >
      <option value="">
        — None (use inline instructions) —
      </option>

      {current &&
        !agents.some((a) => a.id === current) && (
          <option value={current}>
            Unavailable agent
          </option>
        )}

      {agents.map((a) => (
        <option key={a.id} value={a.id}>
          {a.name}
        </option>
      ))}
    </select>
  );
}

function IntegrationSelect({
  controlId,
  value,
  onChange,
}: {
  controlId: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const query = useQuery({
    queryKey: ["integrations"],
    queryFn: fetchIntegrations,
  });

  const integrations =
    query.data?.integrations.filter(
      (i) => i.status === "connected",
    ) ?? [];

  const current =
    typeof value === "string"
      ? value
      : "";

  if (query.isPending) {
    return (
      <div className="flex h-9 items-center rounded-lg border border-white/[0.07] bg-white/[0.025] px-3 text-[11px] text-white/45">
        Loading integrations…
      </div>
    );
  }

  if (query.isError) {
    return (
      <p className="rounded-lg border border-destructive/20 bg-destructive/5 p-2.5 text-[11px] leading-relaxed text-destructive">
        Could not load integrations.
      </p>
    );
  }

  if (integrations.length === 0) {
    return (
      <div className="rounded-lg border border-white/[0.07] bg-white/[0.02] p-3">
        <p className="text-[11px] leading-relaxed text-white/45">
          No connected integrations.{" "}
          <Link
            href="/integrations"
            className="text-primary underline-offset-2 hover:underline"
          >
            Connect one
          </Link>
          .
        </p>
      </div>
    );
  }

  return (
    <select
      id={controlId}
      value={current}
      onChange={(e) =>
        onChange(e.target.value || null)
      }
      className={cn(
        fieldControlClass,
        "px-3 py-1 text-sm outline-none",
      )}
    >
      <option value="">
        — Select an integration —
      </option>

      {integrations.map((i) => (
        <option key={i.id} value={i.id}>
          {i.name} ({i.type})
        </option>
      ))}
    </select>
  );
}

function JsonField({
  controlId,
  value,
  placeholder,
  onChange,
}: {
  controlId: string;
  value: unknown;
  placeholder: string | null;
  onChange: (value: unknown) => void;
}) {
  const [text, setText] =
    React.useState<string>(() =>
      value === undefined ||
      value === null
        ? ""
        : JSON.stringify(
            value,
            null,
            2,
          ),
    );

  const [error, setError] =
    React.useState<string | null>(
      null,
    );

  const nodeKey = controlId;

  React.useEffect(() => {
    setText(
      value === undefined ||
      value === null
        ? ""
        : JSON.stringify(
            value,
            null,
            2,
          ),
    );

    setError(null);

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeKey]);

  function commit() {
    if (text.trim() === "") {
      setError(null);
      onChange(null);
      return;
    }

    try {
      const parsed = JSON.parse(text);

      setError(null);
      onChange(parsed);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Invalid JSON",
      );
    }
  }

  return (
    <div className="min-w-0">
      <div className="relative">
        <Textarea
          id={controlId}
          value={text}
          rows={5}
          aria-invalid={
            error
              ? true
              : undefined
          }
          placeholder={
            placeholder ??
            '{ "key": "value" }'
          }
          className={cn(
            textAreaClass,
            "resize-y pr-9 font-mono text-xs leading-relaxed",
            error &&
              "border-destructive/40 focus-visible:border-destructive/60",
          )}
          onChange={(e) =>
            setText(e.target.value)
          }
          onBlur={commit}
        />

        <Braces className="pointer-events-none absolute right-3 top-3 size-3.5 text-white/20" />
      </div>

      {error && (
        <p className="mt-1.5 break-words rounded-md bg-destructive/5 px-2 py-1 text-[10px] leading-relaxed text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

function VariableHint({
  upstreamIds,
}: {
  upstreamIds: string[];
}) {
  if (upstreamIds.length === 0) {
    return null;
  }

  return (
    <div className="rounded-xl border border-primary/[0.12] bg-primary/[0.035] p-3">
      <div className="mb-2 flex items-center gap-2">
        <div className="flex size-6 items-center justify-center rounded-lg bg-primary/[0.12] text-primary">
          <Variable className="size-3.5" />
        </div>

        <div>
          <p className="text-[11px] font-semibold text-white/80">
            Available variables
          </p>

          <p className="text-[11px] text-white/45">
            Use outputs from upstream nodes
          </p>
        </div>
      </div>

      <p className="text-[11px] leading-relaxed text-white/45">
        Reference upstream output with{" "}
        <code className="rounded-md border border-white/[0.06] bg-black/20 px-1.5 py-0.5 font-mono text-[10px] text-primary/90">
          {"{{nodeId.field}}"}
        </code>
      </p>

      <div className="mt-2.5 flex min-w-0 flex-wrap gap-1.5">
        {upstreamIds.map((id) => (
          <Badge
            key={id}
            variant="outline"
            className="max-w-full break-all border-primary/[0.14] bg-primary/[0.04] font-mono text-[10px] font-normal text-white/60"
          >
            {`{{${id}}}`}
          </Badge>
        ))}
      </div>
    </div>
  );
}