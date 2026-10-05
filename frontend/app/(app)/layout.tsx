import { cookies } from "next/headers";
import { AppGuard } from "@/components/app/app-guard";
import { AppShell } from "@/components/app/app-shell";
import { SessionBootstrapper } from "@/components/session-bootstrapper";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const cookieStore = cookies();
  const defaultCollapsed =
    (cookieStore.get("vantis_sidebar_collapsed") ??
      cookieStore.get("oblayer_sidebar_collapsed") ??
      cookieStore.get("tracerun_sidebar_collapsed"))?.value === "true";

  return (
    <>
      <SessionBootstrapper />
      <AppGuard>
        <AppShell defaultCollapsed={defaultCollapsed}>{children}</AppShell>
      </AppGuard>
    </>
  );
}
