"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { useState } from "react";
import { Toaster } from "sonner";

/**
 * Client-side app providers: theme (light/dark/system), TanStack Query,
 * and toast notifications. Mounted once at the root layout.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            refetchIntervalInBackground: false,
            retry: 1,
          },
        },
      }),
  );

  return (
    <ThemeProvider
      attribute="class"
      defaultTheme="dark"
      enableSystem
      disableTransitionOnChange
    >
      <QueryClientProvider client={queryClient}>
        {children}
        <Toaster
          position="bottom-right"
          theme="dark"
          toastOptions={{
            className:
              "!bg-[#0a0a0c] !text-white !border-white/[0.08] !shadow-2xl !rounded-xl !text-xs",
            descriptionClassName: "!text-white/50 !text-[11px]",
          }}
          closeButton
        />
      </QueryClientProvider>
    </ThemeProvider>
  );
}
