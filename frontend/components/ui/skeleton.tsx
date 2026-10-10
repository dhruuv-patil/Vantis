import * as React from "react";
import { cn } from "@/lib/utils";

/** Loading placeholder with a shimmer sweep. Size it with `className`. */
const Skeleton = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("relative overflow-hidden rounded-md bg-muted dark:bg-white/[0.06]", className)}
    {...props}
  >
    <div className="absolute inset-0 -translate-x-full animate-shimmer bg-gradient-to-r from-transparent via-background/60 to-transparent" />
  </div>
));
Skeleton.displayName = "Skeleton";

export { Skeleton };
