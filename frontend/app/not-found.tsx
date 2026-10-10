"use client";

import Link from "next/link";
import { FileQuestion } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LogoMark } from "@/components/brand/logo";

export default function RootNotFound() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-6 py-12 text-center text-foreground dark:bg-black dark:text-white">
      <div className="flex items-center gap-2 mb-8">
        <LogoMark className="size-6" />
        <span className="text-sm font-semibold tracking-tight text-foreground/90 dark:text-white/90">Vantis</span>
      </div>
      <div className="flex size-12 items-center justify-center rounded-xl border border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0c] mb-4">
        <FileQuestion className="size-6 text-muted-foreground dark:text-white/40" />
      </div>
      <h1 className="text-2xl font-semibold tracking-tight text-foreground dark:text-white">404 · Page not found</h1>
      <p className="mt-2 text-sm text-muted-foreground dark:text-white/50 max-w-sm">
        The page you are looking for does not exist or has been moved.
      </p>
      <div className="mt-6">
        <Link href="/">
          <Button size="sm">Return home</Button>
        </Link>
      </div>
    </main>
  );
}
