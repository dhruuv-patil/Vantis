import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[11px] font-medium leading-4 transition-colors focus:outline-none focus:ring-1 focus:ring-ring [&_svg]:size-3 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "border border-border bg-muted/60 text-foreground/80 dark:border-white/[0.08] dark:bg-white/[0.04] dark:text-white/80",
        secondary: "border border-border/80 bg-muted/40 text-muted-foreground dark:border-white/[0.06] dark:bg-white/[0.03] dark:text-white/60",
        outline: "border border-border text-foreground/80 dark:border-white/[0.12] dark:text-white/70",
        success: "border border-emerald-500/25 bg-emerald-500/10 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/[0.08] dark:text-emerald-300",
        warning: "border border-amber-500/25 bg-amber-500/10 text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/[0.08] dark:text-amber-300",
        destructive: "border border-red-500/25 bg-red-500/10 text-red-700 dark:border-red-500/20 dark:bg-red-500/[0.08] dark:text-red-300",
        danger: "border border-red-500/25 bg-red-500/10 text-red-700 dark:border-red-500/20 dark:bg-red-500/[0.08] dark:text-red-300",
        info: "border border-blue-500/25 bg-blue-500/10 text-blue-700 dark:border-blue-500/20 dark:bg-blue-500/[0.08] dark:text-blue-300",
        active: "border border-emerald-500/30 bg-emerald-500/15 text-emerald-700 dark:border-emerald-500/25 dark:bg-emerald-500/[0.10] dark:text-emerald-300",
        brand: "border border-brand-500/25 bg-brand-500/10 text-brand-600 dark:border-brand-500/20 dark:bg-brand-500/10 dark:text-brand-400",
      },
    },
    defaultVariants: { variant: "default" },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <span className={cn(badgeVariants({ variant }), className)} {...props} />
  );
}

export { Badge, badgeVariants };
