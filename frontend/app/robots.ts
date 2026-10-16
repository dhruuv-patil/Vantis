import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  const baseUrl = process.env.NEXT_PUBLIC_APP_URL || "https://vantis.run";

  return {
    rules: [
      {
        userAgent: "*",
        allow: "/",
        disallow: [
          "/api/",
          "/dashboard",
          "/workflows",
          "/incidents",
          "/reliability",
          "/executions",
          "/settings",
          "/team",
          "/billing",
          "/mock-preview",
          "/agents",
          "/templates",
          "/home",
          "/logs",
          "/login",
          "/signup",
          "/register",
          "/forgot-password",
          "/reset-password",
          "/verify-email",
          "/check-email",
        ],
      },
    ],
    sitemap: `${baseUrl}/sitemap.xml`,
  };
}
