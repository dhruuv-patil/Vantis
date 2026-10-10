"use client";

import * as React from "react";
import { useTheme } from "next-themes";
import { Moon, Sun, Laptop } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

interface ThemeToggleProps {
  className?: string;
  variant?: "button" | "dropdown" | "segmented";
  size?: "default" | "sm" | "icon" | "icon-sm";
}

export function ThemeToggle({
  className,
  variant = "button",
  size = "icon-sm",
}: ThemeToggleProps) {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = React.useState(false);

  React.useEffect(() => {
    setMounted(true);
  }, []);

  if (!mounted) {
    // Avoid hydration mismatch by rendering placeholder of same dimension
    return (
      <div
        className={cn(
          "size-8 rounded-lg border border-border/40 bg-muted/20 opacity-0",
          className,
        )}
        aria-hidden="true"
      />
    );
  }

  const isDark = resolvedTheme === "dark";

  // Quick 1-click toggle between light and dark mode
  const toggleTheme = () => {
    setTheme(isDark ? "light" : "dark");
  };

  if (variant === "segmented") {
    return (
      <div
        className={cn(
          "inline-flex items-center rounded-lg border border-border bg-muted/40 p-1 text-xs",
          className,
        )}
        role="group"
        aria-label="Theme selection"
      >
        <button
          type="button"
          onClick={() => setTheme("light")}
          className={cn(
            "flex items-center gap-1.5 rounded-md px-3 py-1.5 font-medium transition-colors",
            theme === "light"
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          <Sun className="size-3.5" />
          <span>Light</span>
        </button>

        <button
          type="button"
          onClick={() => setTheme("dark")}
          className={cn(
            "flex items-center gap-1.5 rounded-md px-3 py-1.5 font-medium transition-colors",
            theme === "dark"
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          <Moon className="size-3.5" />
          <span>Dark</span>
        </button>

        <button
          type="button"
          onClick={() => setTheme("system")}
          className={cn(
            "flex items-center gap-1.5 rounded-md px-3 py-1.5 font-medium transition-colors",
            theme === "system"
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          <Laptop className="size-3.5" />
          <span>System</span>
        </button>
      </div>
    );
  }

  if (variant === "dropdown") {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size={size}
            className={cn(
              "relative rounded-lg border border-border/80 bg-background/50 hover:bg-muted text-foreground transition-colors",
              className,
            )}
            title={`Theme: ${theme ?? "system"}`}
            aria-label="Select theme"
          >
            {isDark ? (
              <Moon className="size-4 transition-transform duration-200" />
            ) : (
              <Sun className="size-4 transition-transform duration-200" />
            )}
            <span className="sr-only">Toggle theme</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-36">
          <DropdownMenuItem
            onClick={() => setTheme("light")}
            className="flex items-center gap-2 cursor-pointer"
          >
            <Sun className="size-4" />
            <span>Light</span>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => setTheme("dark")}
            className="flex items-center gap-2 cursor-pointer"
          >
            <Moon className="size-4" />
            <span>Dark</span>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => setTheme("system")}
            className="flex items-center gap-2 cursor-pointer"
          >
            <Laptop className="size-4" />
            <span>System</span>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    );
  }

  // Default "button" toggle
  return (
    <Button
      type="button"
      variant="ghost"
      size={size}
      onClick={toggleTheme}
      className={cn(
        "relative size-8 rounded-lg border border-border/80 bg-background/60 text-muted-foreground hover:text-foreground hover:bg-muted/80 transition-all duration-200 dark:border-white/[0.08] dark:bg-white/[0.025] dark:text-white/60 dark:hover:text-white dark:hover:bg-white/[0.06]",
        className,
      )}
      aria-label={isDark ? "Switch to light theme" : "Switch to dark theme"}
      title={isDark ? "Switch to light theme" : "Switch to dark theme"}
    >
      <Sun
        className={cn(
          "size-4 transition-all duration-300",
          isDark
            ? "rotate-90 scale-0 opacity-0 absolute"
            : "rotate-0 scale-100 opacity-100",
        )}
      />
      <Moon
        className={cn(
          "size-4 transition-all duration-300",
          isDark
            ? "rotate-0 scale-100 opacity-100"
            : "-rotate-90 scale-0 opacity-0 absolute",
        )}
      />
      <span className="sr-only">
        {isDark ? "Switch to light mode" : "Switch to dark mode"}
      </span>
    </Button>
  );
}
