import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[11px] font-medium leading-4 transition-colors focus:outline-none focus:ring-1 focus:ring-white/20 [&_svg]:size-3 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "border border-white/[0.08] bg-white/[0.04] text-white/80",
        secondary: "border border-white/[0.06] bg-white/[0.03] text-white/60",
        outline: "border border-white/[0.12] text-white/70",
        success: "border border-emerald-500/20 bg-emerald-500/[0.08] text-emerald-300",
        warning: "border border-amber-500/20 bg-amber-500/[0.08] text-amber-300",
        destructive: "border border-red-500/20 bg-red-500/[0.08] text-red-300",
        danger: "border border-red-500/20 bg-red-500/[0.08] text-red-300",
        info: "border border-blue-500/20 bg-blue-500/[0.08] text-blue-300",
        active: "border border-emerald-500/25 bg-emerald-500/[0.10] text-emerald-300",
        brand: "border border-brand-500/20 bg-brand-500/10 text-brand-400",
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
