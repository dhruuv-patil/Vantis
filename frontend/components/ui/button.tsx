import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-white/20 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-sm hover:bg-primary/90",
        contrast:
          "border border-border bg-primary text-primary-foreground shadow-sm hover:bg-primary/90",
        destructive:
          "border border-red-500/20 bg-red-500/10 text-red-400 shadow-sm hover:bg-red-500/20",
        outline:
          "border border-border bg-background text-foreground shadow-sm hover:border-border-strong hover:bg-muted hover:text-foreground dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/80 dark:hover:border-white/[0.14] dark:hover:bg-white/[0.05] dark:hover:text-white",
        secondary:
          "border border-border bg-secondary text-secondary-foreground shadow-sm hover:bg-secondary/80 dark:border-white/[0.06] dark:bg-white/[0.05] dark:text-white/80 dark:hover:bg-white/[0.08] dark:hover:text-white",
        ghost:
          "text-muted-foreground hover:bg-muted hover:text-foreground dark:text-white/60 dark:hover:bg-white/[0.05] dark:hover:text-white",
        link: "text-primary underline-offset-4 hover:underline dark:text-white",
      },
      size: {
        default: "h-10 px-4 py-2",
        sm: "h-8 px-3 text-xs",
        lg: "h-11 px-6",
        icon: "h-10 w-10",
        "icon-sm": "h-8 w-8",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button"
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button, buttonVariants }
