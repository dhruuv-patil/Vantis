import type { Metadata } from "next";

import "./globals.css";
import { Providers } from "@/components/providers";

export const metadata: Metadata = {
  title: {
    default: "Vantis — Reliability infrastructure for automation",
    template: "%s · Vantis",
  },
  description:
    "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures.",
  metadataBase: new URL("https://vantis.run"),
  manifest: "/site.webmanifest",

  icons: {
    icon: "/logo-mark.svg",
    shortcut: "/logo-mark.svg",
    apple: "/logo-mark.svg",
  },

  openGraph: {
    title: "Vantis — Reliability infrastructure for automation",
    description:
      "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures.",
    siteName: "Vantis",
    type: "website",
  },

  twitter: {
    card: "summary_large_image",
    title: "Vantis — Reliability infrastructure for automation",
    description:
      "Reliability infrastructure for automation. Monitor workflows, detect anomalies, and investigate failures.",
  },
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
      style={{ backgroundColor: "#000000" }}
    >
      <head>
        <link
          rel="preload"
          as="image"
          href="https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=2400&q=88"
        />
      </head>

      <body
        className="min-h-screen font-sans text-foreground antialiased"
        style={{ backgroundColor: "#000000" }}
      >
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}