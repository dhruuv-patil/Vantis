"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

export interface SegmentedOption<T extends string = string> {
  value: T;
  label: React.ReactNode;
  icon?: React.ReactNode;
  disabled?: boolean;
}

export interface SegmentedControlProps<T extends string = string> {
  /** Array of tab values and labels (tabs or options) */
  tabs?: readonly SegmentedOption<T>[];
  options?: readonly SegmentedOption<T>[];
  /** Currently active tab value */
  value?: T;
  defaultValue?: T;
  /** Callback when tab changes */
  onValueChange?: (value: T) => void;
  /** Accessible label */
  label?: string;
  /** Size variant: default (h-10, h-8 pill) or compact (h-8) */
  size?: "default" | "sm";
  /** Additional className for the container */
  className?: string;
  /** Whether the control is disabled */
  disabled?: boolean;
}

/**
 * Unified segmented control for Vantis.
 * Follows the Templates visual benchmark: dark surface container,
 * crisp white/[0.08] border, subtle rounded-lg / rounded-md tabs.
 */
export function SegmentedControl<T extends string = string>({
  tabs,
  options,
  value,
  defaultValue,
  onValueChange,
  label = "View selector",
  size = "default",
  className,
  disabled = false,
}: SegmentedControlProps<T>) {
  const items = (options ?? tabs ?? []) as readonly SegmentedOption<T>[];
  const [internal, setInternal] = React.useState<T>(
    defaultValue ?? items[0]?.value ?? ("" as T),
  );

  const isControlled = value !== undefined;
  const selected = isControlled ? value : internal;

  const handleSelect = (next: T) => {
    if (!isControlled) {
      setInternal(next);
    }
    onValueChange?.(next);
  };

  return (
    <div
      role="radiogroup"
      aria-label={label}
      className={cn(
        "inline-flex items-center gap-1 rounded-lg border border-white/[0.08] bg-[#0a0a0c] p-1 transition-all duration-200",
        size === "sm" ? "h-8" : "h-10",
        disabled && "opacity-50 pointer-events-none",
        className,
      )}
    >
      {items.map((item) => {
        const active = item.value === selected;
        const isDisabled = disabled || item.disabled;

        return (
          <button
            key={item.value}
            type="button"
            role="radio"
            aria-checked={active}
            disabled={isDisabled}
            onClick={() => handleSelect(item.value)}
            className={cn(
              "relative z-10 inline-flex items-center justify-center gap-1.5 rounded-md px-3 font-medium transition-all duration-150 ease-out",
              size === "sm" ? "h-6 text-[11px]" : "h-8 text-xs",
              "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-white/20",
              "disabled:opacity-40 disabled:pointer-events-none",
              active
                ? "bg-white text-black font-semibold shadow-sm"
                : "text-white/45 hover:text-white/80 hover:bg-white/[0.04]",
            )}
          >
            {item.icon && (
              <span className="[&_svg]:size-3.5 [&_svg]:shrink-0 opacity-70">
                {item.icon}
              </span>
            )}
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/** Alias for backward compatibility */
export const SegmentedToggle = SegmentedControl;
export type SegmentedToggleProps = SegmentedControlProps;