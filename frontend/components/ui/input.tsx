import * as React from "react";
import { cn } from "@/lib/utils";

export interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement> {
  isCode?: boolean;
}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, isCode, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          "flex h-10 w-full rounded-lg bg-white/[0.025] border border-white/[0.08] text-white placeholder:text-white/30 px-3.5 py-2 text-sm",
          "transition-all duration-200 focus-visible:outline-none focus-visible:border-white/[0.18] focus-visible:ring-1 focus-visible:ring-white/20",
          "disabled:cursor-not-allowed disabled:opacity-40",
          isCode && "font-mono text-xs",
          className,
        )}
        ref={ref}
        {...props}
      />
    );
  },
);
Input.displayName = "Input";

export { Input };
