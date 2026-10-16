import type { Metadata } from "next";

import "./globals.css";
import { Providers } from "@/components/providers";
import { JsonLd } from "@/components/seo/json-ld";

export const metadata: Metadata = {
  metadataBase: new URL("https://vantis.run"),
  title: {
    default: "Vantis — Reliability infrastructure for automation",
    template: "%s · Vantis",
  },
  description:
    "Reliability infrastructure for automation. Build, monitor workflows, detect anomalies in real time, and investigate failures across n8n, Zapier, Temporal, and custom APIs.",
  keywords: [
    "workflow monitoring",
    "automation reliability",
    "workflow observability",
    "workflow failure investigation",
    "n8n monitoring",
    "Zapier error monitoring",
    "Temporal workflow observability",
    "anomaly detection workflows",
    "automation failure alerts",
    "incident triage for automation",
  ],
  alternates: {
    canonical: "/",
  },
  manifest: "/site.webmanifest",

  icons: {
    icon: "/logo-mark.svg",
    shortcut: "/logo-mark.svg",
    apple: "/logo-mark.svg",
  },

  openGraph: {
    title: "Vantis — Reliability infrastructure for automation",
    description:
      "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures across your automation stack.",
    url: "https://vantis.run",
    siteName: "Vantis",
    locale: "en_US",
    type: "website",
  },

  twitter: {
    card: "summary_large_image",
    title: "Vantis — Reliability infrastructure for automation",
    description:
      "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures across your automation stack.",
  },

  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-video-preview": -1,
      "max-image-preview": "large",
      "max-snippet": -1,
    },
  },
  category: "technology",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
    >
      <body
        className="min-h-screen font-sans bg-background text-foreground antialiased"
      >
        <JsonLd />
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}