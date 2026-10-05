import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Providers } from "@/components/providers";
import { SessionBootstrapper } from "@/components/session-bootstrapper";
import * as apiModule from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";

describe("SessionBootstrapper & Layout Architecture", () => {
  let bootstrapSessionSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    window.matchMedia = (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });

    vi.restoreAllMocks();
    bootstrapSessionSpy = vi
      .spyOn(apiModule, "bootstrapSession")
      .mockResolvedValue(true);
    useAuthStore.setState({
      status: "idle",
      accessToken: null,
      user: null,
      currentOrganization: null,
      currentRole: null,
      memberships: [],
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("does not call bootstrapSession on /login", () => {
    window.history.pushState({}, "", "/login?next=%2Fdashboard");

    render(<SessionBootstrapper />);

    expect(bootstrapSessionSpy).not.toHaveBeenCalled();
  });

  it("does not call bootstrapSession on other public auth routes (/register, /signup, /forgot-password, /reset-password)", () => {
    const publicRoutes = [
      "/register",
      "/signup",
      "/forgot-password",
      "/reset-password",
    ];

    for (const route of publicRoutes) {
      bootstrapSessionSpy.mockClear();
      window.history.pushState({}, "", route);

      render(<SessionBootstrapper />);

      expect(bootstrapSessionSpy).not.toHaveBeenCalled();
    }
  });

  it("calls bootstrapSession on authenticated routes when status is idle", () => {
    window.history.pushState({}, "", "/dashboard");

    render(<SessionBootstrapper />);

    expect(bootstrapSessionSpy).toHaveBeenCalledTimes(1);
  });

  it("does not call bootstrapSession if auth status is already authenticated", () => {
    window.history.pushState({}, "", "/dashboard");
    useAuthStore.setState({ status: "authenticated" });

    render(<SessionBootstrapper />);

    expect(bootstrapSessionSpy).not.toHaveBeenCalled();
  });

  it("does not trigger duplicate bootstrapSession calls under StrictMode remounting", () => {
    window.history.pushState({}, "", "/dashboard");

    const { rerender } = render(<SessionBootstrapper />);
    rerender(<SessionBootstrapper />);

    expect(bootstrapSessionSpy).toHaveBeenCalledTimes(1);
  });

  it("does not include SessionBootstrapper inside global Providers", () => {
    // Navigating to /login and mounting Providers (used by RootLayout)
    // should never invoke bootstrapSession.
    window.history.pushState({}, "", "/login?next=%2Fdashboard");

    render(
      <Providers>
        <div data-testid="child">Public Content</div>
      </Providers>,
    );

    expect(bootstrapSessionSpy).not.toHaveBeenCalled();
  });
});
