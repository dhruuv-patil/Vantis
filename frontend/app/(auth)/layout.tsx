import type { ReactNode } from "react";
import { ThemeToggle } from "@/components/theme-toggle";

export default function AuthLayout({
  children,
}: {
  children: ReactNode;
}) {
  return (
    <main className="relative min-h-screen bg-background text-foreground dark:bg-[#050505] dark:text-white">
      <div className="absolute right-5 top-5">
        <ThemeToggle />
      </div>
      <div className="flex min-h-screen items-center justify-center px-5 py-10">
        <div className="w-full max-w-[400px]">
          {children}
        </div>
      </div>
    </main>
  );
}