"use client";

import { useEffect, useRef } from "react";

import { bootstrapSession } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";

/**
 * Runs the app-boot session recovery exactly once per mount (contract §6.1).
 *
 * Mounted exclusively inside the authenticated application layout ((app)/layout.tsx).
 *
 * The access token lives in memory only, so a reload starts anonymous; one
 * `POST /api/auth/refresh` restores both the token and a fresh user/org snapshot.
 * A React 18 StrictMode double-mount would otherwise fire two refreshes, and
 * refresh rotates the cookie — the second call would present an already-rotated
 * token and trip reuse detection. The ref guard makes it fire once.
 */

const PUBLIC_AUTH_ROUTES = new Set([
  "/login",
  "/register",
  "/signup",
  "/forgot-password",
  "/reset-password",
]);

export function SessionBootstrapper() {
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    // Defense-in-depth: Never attempt session recovery on public authentication pages.
    if (typeof window !== "undefined") {
      const pathname = window.location.pathname;
      if (
        PUBLIC_AUTH_ROUTES.has(pathname) ||
        pathname.startsWith("/login") ||
        pathname.startsWith("/register") ||
        pathname.startsWith("/signup") ||
        pathname.startsWith("/forgot-password") ||
        pathname.startsWith("/reset-password")
      ) {
        return;
      }
    }

    if (useAuthStore.getState().status === "idle") {
      void bootstrapSession();
    }
  }, []);

  return null;
}