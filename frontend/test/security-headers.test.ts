import { describe, expect, it } from "vitest";
import nextConfig, { buildCspHeader } from "../next.config.mjs";

describe("Content Security Policy & Security Headers", () => {
  it("builds a restrictive CSP header without wildcards or unsafe-eval", () => {
    const csp = buildCspHeader();

    // Must have restrictive defaults
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("frame-src 'none'");
    expect(csp).toContain("form-action 'self'");
    expect(csp).toContain("worker-src 'self'");

    // Must NOT contain unsafe-eval or broad wildcard
    expect(csp).not.toContain("unsafe-eval");
    expect(csp).not.toContain("script-src *");
    expect(csp).not.toContain("connect-src *");
    expect(csp).not.toContain("https:*");
    expect(csp).not.toMatch(/img-src\s+[^;]*\*/);

    // Whitelisted image origins
    expect(csp).toContain("https://images.unsplash.com");
    expect(csp).toContain("https://thesvg.org");
    expect(csp).toContain("https://cdn.simpleicons.org");
    expect(csp).toContain("data:");

    // Connect-src backend origins
    expect(csp).toContain("http://localhost:8080");
    expect(csp).toContain("ws://localhost:8080");
  });

  it("dynamically includes NEXT_PUBLIC_API_URL and WebSocket counterpart when set", () => {
    const originalEnv = process.env.NEXT_PUBLIC_API_URL;
    try {
      process.env.NEXT_PUBLIC_API_URL = "https://api.vantis.run";
      const csp = buildCspHeader();
      expect(csp).toContain("https://api.vantis.run");
      expect(csp).toContain("wss://api.vantis.run");
    } finally {
      process.env.NEXT_PUBLIC_API_URL = originalEnv;
    }
  });

  it("configures all required security headers in next.config.mjs headers()", async () => {
    expect(nextConfig.headers).toBeDefined();
    const routeHeaders = await nextConfig.headers!();

    expect(routeHeaders.length).toBeGreaterThan(0);
    const globalRoute = routeHeaders.find((r) => r.source === "/:path*");
    expect(globalRoute).toBeDefined();

    const headersMap = new Map(
      globalRoute!.headers.map((h) => [h.key, h.value])
    );

    // CSP
    expect(headersMap.has("Content-Security-Policy")).toBe(true);
    const csp = headersMap.get("Content-Security-Policy")!;
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).not.toContain("unsafe-eval");

    // Clickjacking & MIME protection
    expect(headersMap.get("X-Frame-Options")).toBe("DENY");
    expect(headersMap.get("X-Content-Type-Options")).toBe("nosniff");

    // Referrer & Permissions & HSTS
    expect(headersMap.get("Referrer-Policy")).toBe("strict-origin-when-cross-origin");
    expect(headersMap.get("Permissions-Policy")).toContain("camera=()");
    expect(headersMap.get("Strict-Transport-Security")).toContain("max-age=31536000");
  });
});
