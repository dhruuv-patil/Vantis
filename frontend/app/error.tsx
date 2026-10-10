"use client";

import { useEffect } from "react";
import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LogoMark } from "@/components/brand/logo";

export default function RootError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Root application error:", error);
  }, [error]);

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-6 py-12 text-center text-foreground dark:bg-black dark:text-white">
      <div className="flex items-center gap-2 mb-8">
        <LogoMark className="size-6" />
        <span className="text-sm font-semibold tracking-tight text-foreground/90 dark:text-white/90">Vantis</span>
      </div>
      <div className="flex size-12 items-center justify-center rounded-xl border border-red-500/20 bg-red-500/[0.05] mb-4">
        <AlertCircle className="size-6 text-red-400" />
      </div>
      <h1 className="text-2xl font-semibold tracking-tight text-foreground dark:text-white">Something went wrong</h1>
      <p className="mt-2 text-sm text-muted-foreground dark:text-white/50 max-w-sm">
        An unhandled error occurred. Please try again or return to home.
      </p>
      <div className="mt-6 flex items-center justify-center gap-3">
        <Button variant="outline" size="sm" onClick={() => reset()}>
          Try again
        </Button>
        <Link href="/">
          <Button size="sm">Return home</Button>
        </Link>
      </div>
    </main>
  );
}
