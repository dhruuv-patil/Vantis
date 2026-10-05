"use client";

import * as React from "react";
import {
  BrandIcon,
  resolveTheSvgSlug,
} from "@/components/app/builder/node-visuals";
import { resolveIcon } from "@/components/app/builder/shared";
import { Plug } from "lucide-react";
import { cn, initialsFrom } from "@/lib/utils";

export interface ProviderIconProps {
  /** Lucide icon name, wire type, or provider slug */
  icon?: string;
  /** Integration wire type, e.g. "github", "postgresql", "slack" */
  provider?: string;
  /** Human-readable provider name, e.g. "GitHub", "PostgreSQL" */
  name?: string;
  className?: string;
  theme?: "dark" | "light" | "auto";
  fallbackInitials?: boolean;
}

export function ProviderIcon({
  icon,
  provider,
  name,
  className = "size-6",
  theme = "auto",
  fallbackInitials = false,
}: ProviderIconProps) {
  const slug = React.useMemo(
    () => resolveTheSvgSlug(provider, name, icon),
    [provider, name, icon],
  );

  if (slug) {
    return (
      <BrandIcon
        slug={slug}
        name={name}
        className={className}
        theme={theme}
        fallbackIcon={resolveIcon(icon)}
      />
    );
  }

  // Generic or capability-based icon (e.g., Mail, Webhook, Database)
  if (icon) {
    const IconComponent = resolveIcon(icon);
    return <IconComponent className={className} aria-hidden="true" />;
  }

  if (fallbackInitials && (name || provider)) {
    return (
      <div
        className={cn(
          className,
          "flex items-center justify-center rounded bg-zinc-100 font-mono text-[11px] font-semibold text-zinc-700 select-none dark:bg-white/[0.08] dark:text-white/80",
        )}
        aria-hidden="true"
      >
        {initialsFrom(name || provider || "?")}
      </div>
    );
  }

  return <Plug className={className} aria-hidden="true" />;
}