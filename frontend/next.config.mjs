/** @type {import('next').NextConfig} */

/**
 * Builds the Content Security Policy header value based on actual application requirements.
 * - default-src: restrictive 'self'
 * - script-src: 'self' + 'unsafe-inline' (required for Next.js App Router RSC flight stream
 *   and next-themes initial script in pre-rendered static pages)
 *   + 'unsafe-eval' ONLY in development for the Next.js development runtime
 * - style-src: 'self' + 'unsafe-inline' (required for React inline styles, @xyflow/react canvas
 *   node positioning/transforms, and Framer Motion)
 * - img-src: 'self', data:, and strictly whitelisted external CDNs (unsplash, thesvg, simpleicons)
 * - font-src: 'self', data:
 * - connect-src: 'self', backend API, and WebSocket origins
 * - object-src: 'none'
 * - base-uri: 'self'
 * - form-action: 'self'
 * - frame-ancestors: 'none'
 * - frame-src: 'none'
 * - worker-src: 'self'
 * - manifest-src: 'self'
 */
export function buildCspHeader() {
  const connectOrigins = new Set([
    "'self'",
    "http://localhost:8080",
    "ws://localhost:8080",
  ]);

  const publicApiUrl = process.env.NEXT_PUBLIC_API_URL;

  if (publicApiUrl) {
    try {
      const parsed = new URL(publicApiUrl);

      connectOrigins.add(parsed.origin);

      const wsProtocol = parsed.protocol === "https:" ? "wss:" : "ws:";
      connectOrigins.add(`${wsProtocol}//${parsed.host}`);
    } catch {
      // Invalid URL format; preserve default local origins
    }
  }

  const isDev = process.env.NODE_ENV === "development";

  const directives = {
    "default-src": ["'self'"],

    "script-src": [
      "'self'",
      "'unsafe-inline'",
      ...(isDev ? ["'unsafe-eval'"] : []),
    ],

    "style-src": ["'self'", "'unsafe-inline'"],

    "img-src": [
      "'self'",
      "data:",
      "https://images.unsplash.com",
      "https://thesvg.org",
      "https://cdn.simpleicons.org",
    ],

    "font-src": ["'self'", "data:"],

    "connect-src": Array.from(connectOrigins),

    "object-src": ["'none'"],

    "base-uri": ["'self'"],

    "form-action": ["'self'"],

    "frame-ancestors": ["'none'"],

    "frame-src": ["'none'"],

    "worker-src": ["'self'"],

    "manifest-src": ["'self'"],

    "block-all-mixed-content": [],

    "upgrade-insecure-requests": [],
  };

  return Object.entries(directives)
    .map(([key, values]) =>
      values.length > 0 ? `${key} ${values.join(" ")}` : key
    )
    .join("; ");
}

const nextConfig = {
  reactStrictMode: true,

  eslint: {
    // Lint is run explicitly via `npm run lint`;
    // don't fail production builds on it.
    ignoreDuringBuilds: true,
  },

  async headers() {
    return [
      {
        source: "/:path*",

        headers: [
          {
            key: "Content-Security-Policy",
            value: buildCspHeader(),
          },

          {
            key: "X-Frame-Options",
            value: "DENY",
          },

          {
            key: "X-Content-Type-Options",
            value: "nosniff",
          },

          {
            key: "Referrer-Policy",
            value: "strict-origin-when-cross-origin",
          },

          {
            key: "Permissions-Policy",
            value:
              "camera=(), microphone=(), geolocation=(), browsing-topics=()",
          },

          {
            key: "Strict-Transport-Security",
            value:
              "max-age=31536000; includeSubDomains; preload",
          },
        ],
      },
    ];
  },
};

export default nextConfig;