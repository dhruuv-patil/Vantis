"use client";

import * as React from "react";
import { Plug, Boxes, Bot, UserCheck, Webhook, Mail, Globe2, CalendarDays, Code2, GitFork, Database, Zap, Clock3, HardDrive, ShieldCheck, Sparkles, type LucideIcon } from "lucide-react";
import { cn, initialsFrom } from "@/lib/utils";
import { resolveIcon } from "@/components/app/builder/shared";

/* ==========================================================================
   BRAND RULES (slug on theSVG / local -> identifiers/keywords that map to it)
   Order matters: more specific rules first.
   Keywords match whole underscore-separated tokens.
   ========================================================================== */

export const BRAND_RULES: ReadonlyArray<readonly [string, readonly string[]]> = [
  // Developer & CI/CD
  ["github-actions", ["github_actions", "githubactions", "actions"]],
  ["bitbucket", ["bitbucket"]],
  ["gitlab", ["gitlab"]],
  ["github", ["github", "gh"]],
  ["sentry", ["sentry"]],
  ["pagerduty", ["pagerduty", "pd"]],
  ["vercel", ["vercel"]],
  ["docker", ["docker"]],
  ["kubernetes", ["kubernetes", "k8s"]],

  // Google Workspace & Cloud
  ["google-sheets", ["google_sheets", "googlesheets", "sheets", "googlesheet", "sheet"]],
  ["google-calendar", ["google_calendar", "googlecalendar", "calendar", "gcal"]],
  ["google-docs", ["google_docs", "googledocs", "gdocs"]],
  ["google-drive", ["google_drive", "googledrive", "gdrive", "drive"]],
  ["google-cloud", ["google_cloud", "googlecloud", "gcp", "gcs"]],
  ["gmail", ["gmail"]],

  // Microsoft
  ["microsoft-teams", ["microsoft_teams", "ms_teams", "teams", "msteams"]],
  ["microsoft-onedrive", ["microsoft_onedrive", "onedrive"]],
  ["azure", ["azure", "microsoft_azure", "ms_azure"]],

  // Cloud & Storage
  ["amazon-s3", ["s3", "aws_s3", "amazons3", "amazon_s3"]],
  ["aws", ["aws", "amazon_web_services", "amazonaws"]],
  ["cloudflare", ["cloudflare"]],
  ["digitalocean", ["digitalocean", "digital_ocean"]],
  ["dropbox", ["dropbox"]],
  ["box", ["box"]],

  // Databases
  ["postgresql", ["postgresql", "postgres", "psql"]],
  ["mysql", ["mysql"]],
  ["mongodb", ["mongodb", "mongo"]],
  ["redis", ["redis"]],
  ["supabase", ["supabase"]],
  ["airtable", ["airtable"]],

  // Project Management & Tracking
  ["linear", ["linear"]],
  ["jira", ["jira", "atlassian_jira"]],
  ["asana", ["asana"]],
  ["trello", ["trello"]],
  ["clickup", ["clickup"]],
  ["monday", ["monday", "monday_com"]],

  // CRM & Sales
  ["salesforce", ["salesforce", "sfdc"]],
  ["hubspot", ["hubspot"]],
  ["pipedrive", ["pipedrive"]],
  ["zoho", ["zoho", "zoho_crm", "zohocrm"]],

  // Communication
  ["slack", ["slack"]],
  ["discord", ["discord"]],
  ["telegram", ["telegram"]],
  ["twilio", ["twilio"]],

  // Email
  ["sendgrid", ["sendgrid"]],
  ["resend", ["resend"]],

  // Payments & Productivity
  ["stripe", ["stripe"]],
  ["notion", ["notion"]],

  // AI & LLM
  ["openai", ["openai", "chatgpt"]],
  ["anthropic", ["anthropic", "claude"]],
  ["cohere", ["cohere"]],
  ["hugging-face", ["huggingface", "hugging_face"]],
  ["mistral", ["mistral", "mistralai"]],
  ["groq", ["groq"]],

  // Automation & Orchestration
  ["n8n", ["n8n"]],
  ["zapier", ["zapier"]],
  ["temporal", ["temporal"]],
  ["make", ["make", "integromat"]],
];

/* ==========================================================================
   SYSTEM / CAPABILITY ICONS
   Mapping of native node & template capabilities to icons & accents
   ========================================================================== */

export const CAPABILITY_ICONS: Record<string, { icon: LucideIcon; color: string; label: string }> = {
  ai: { icon: Bot, color: "#8B5CF6", label: "AI Agent" },
  llm: { icon: Bot, color: "#8B5CF6", label: "LLM" },
  agent: { icon: Bot, color: "#8B5CF6", label: "Agent" },
  webhook: { icon: Webhook, color: "#F59E0B", label: "Webhook" },
  http: { icon: Globe2, color: "#3B82F6", label: "HTTP API" },
  rest: { icon: Globe2, color: "#3B82F6", label: "REST API" },
  approval: { icon: UserCheck, color: "#10B981", label: "Human Approval" },
  email: { icon: Mail, color: "#EF4444", label: "Email" },
  smtp: { icon: Mail, color: "#EF4444", label: "SMTP" },
  calendar: { icon: CalendarDays, color: "#3B82F6", label: "Calendar" },
  schedule: { icon: Clock3, color: "#F59E0B", label: "Schedule / Cron" },
  cron: { icon: Clock3, color: "#F59E0B", label: "Cron" },
  code: { icon: Code2, color: "#38BDF8", label: "Code / Script" },
  transform: { icon: Code2, color: "#38BDF8", label: "Transform" },
  condition: { icon: GitFork, color: "#FB923C", label: "Condition / Branch" },
  filter: { icon: GitFork, color: "#FB923C", label: "Filter" },
  database: { icon: Database, color: "#60A5FA", label: "Database" },
  trigger: { icon: Zap, color: "#F59E0B", label: "Trigger" },
  storage: { icon: HardDrive, color: "#38BDF8", label: "Storage" },
  security: { icon: ShieldCheck, color: "#F43F5E", label: "Security" },
};

function normalize(value?: string): string {
  return (value ?? "")
    .trim()
    .toLowerCase()
    .replace(/[\s./-]+/g, "_");
}

const slugResolutionCache = new Map<string, string | null>();

/**
 * Resolves a brand icon slug from type, label, icon, name, or provider.
 * Optimized with an in-memory resolution cache for repeated lookups.
 */
export function resolveTheSvgSlug(
  type?: string,
  label?: string,
  icon?: string,
): string | null {
  const cacheKey = `${type ?? ""}::${label ?? ""}::${icon ?? ""}`;
  if (slugResolutionCache.has(cacheKey)) {
    return slugResolutionCache.get(cacheKey)!;
  }

  for (const raw of [type, label, icon]) {
    const value = normalize(raw);
    if (!value) continue;

    // "make" is a common verb, only match exact or explicit make/integromat
    if (value === "make" || value === "integromat") {
      slugResolutionCache.set(cacheKey, "make");
      return "make";
    }

    const haystack = `_${value}_`;

    for (const [slug, keywords] of BRAND_RULES) {
      if (keywords.some((k) => haystack.includes(`_${k}_`))) {
        slugResolutionCache.set(cacheKey, slug);
        return slug;
      }
    }
  }

  slugResolutionCache.set(cacheKey, null);
  return null;
}

export const resolveBrandSlug = resolveTheSvgSlug;

/**
 * Brands whose default logo is black / near-black monochrome and disappears
 * on a dark surface without inversion. In dark mode, rendered as white silhouettes;
 * in light mode, rendered in crisp black / original color.
 */
export const MONO_DARK_SLUGS = new Set([
  "github",
  "github-actions",
  "vercel",
  "notion",
  "openai",
  "anthropic",
  "resend",
  "sentry",
  "pipedrive",
  "temporal",
  "aws",
  "linear",
  "cohere",
]);

export function isMonoDarkBrand(slug: string): boolean {
  return MONO_DARK_SLUGS.has(slug);
}

export const BRAND_TITLES: Record<string, string> = {
  github: "GitHub",
  "github-actions": "GitHub Actions",
  gitlab: "GitLab",
  bitbucket: "Bitbucket",
  "google-sheets": "Google Sheets",
  "google-calendar": "Google Calendar",
  "google-docs": "Google Docs",
  "google-drive": "Google Drive",
  "google-cloud": "Google Cloud",
  "microsoft-teams": "Microsoft Teams",
  "microsoft-onedrive": "OneDrive",
  azure: "Microsoft Azure",
  "amazon-s3": "Amazon S3",
  aws: "AWS",
  salesforce: "Salesforce",
  pagerduty: "PagerDuty",
  vercel: "Vercel",
  sentry: "Sentry",
  linear: "Linear",
  jira: "Jira",
  asana: "Asana",
  trello: "Trello",
  clickup: "ClickUp",
  monday: "Monday.com",
  airtable: "Airtable",
  hubspot: "HubSpot",
  pipedrive: "Pipedrive",
  zoho: "Zoho",
  slack: "Slack",
  discord: "Discord",
  telegram: "Telegram",
  twilio: "Twilio",
  sendgrid: "SendGrid",
  resend: "Resend",
  gmail: "Gmail",
  notion: "Notion",
  stripe: "Stripe",
  openai: "OpenAI",
  anthropic: "Anthropic",
  cohere: "Cohere",
  "hugging-face": "Hugging Face",
  mistral: "Mistral AI",
  groq: "Groq",
  n8n: "n8n",
  make: "Make",
  zapier: "Zapier",
  temporal: "Temporal",
  postgresql: "PostgreSQL",
  mysql: "MySQL",
  mongodb: "MongoDB",
  redis: "Redis",
  docker: "Docker",
  kubernetes: "Kubernetes",
  cloudflare: "Cloudflare",
  digitalocean: "DigitalOcean",
  dropbox: "Dropbox",
  box: "Box",
  supabase: "Supabase",
};

export const BRAND_COLORS: Record<string, string> = {
  github: "#181717",
  "github-actions": "#2088FF",
  gitlab: "#FC6D26",
  bitbucket: "#0052CC",
  "google-sheets": "#34A853",
  "google-calendar": "#1A73E8",
  "google-docs": "#4285F4",
  "google-drive": "#34A853",
  "google-cloud": "#4285F4",
  "microsoft-teams": "#6264A7",
  "microsoft-onedrive": "#0078D4",
  azure: "#0078D4",
  "amazon-s3": "#569A31",
  aws: "#FF9900",
  salesforce: "#00A1E0",
  pagerduty: "#06AC38",
  vercel: "#000000",
  sentry: "#362D59",
  linear: "#5E6AD2",
  jira: "#0C66E4",
  asana: "#F06A6A",
  trello: "#0052CC",
  clickup: "#7B68EE",
  monday: "#FF3D57",
  airtable: "#18BFFF",
  hubspot: "#FF7A59",
  pipedrive: "#017737",
  zoho: "#C8232C",
  slack: "#E01E5A",
  discord: "#5865F2",
  telegram: "#229ED9",
  twilio: "#F22F46",
  sendgrid: "#1A82E2",
  resend: "#000000",
  gmail: "#EA4335",
  notion: "#000000",
  stripe: "#635BFF",
  openai: "#10A37F",
  anthropic: "#D97757",
  cohere: "#39594C",
  "hugging-face": "#FFD21E",
  mistral: "#FA520F",
  groq: "#F55036",
  n8n: "#EA4B71",
  make: "#6D00CC",
  zapier: "#FF4A00",
  temporal: "#7C3AED",
  postgresql: "#4169E1",
  mysql: "#4479A1",
  mongodb: "#47A248",
  redis: "#DC382D",
  docker: "#2496ED",
  kubernetes: "#326CE5",
  cloudflare: "#F38020",
  digitalocean: "#0080FF",
  dropbox: "#0061FF",
  box: "#0061D5",
  supabase: "#3ECF8E",
};

export function theSvgUrl(slug: string): string {
  return `https://thesvg.org/icons/${slug}/default.svg`;
}

export function getBrandTitle(slug: string, fallback?: string): string {
  return BRAND_TITLES[slug] ?? fallback ?? slug;
}

export function getBrandColor(slug: string, fallback = "#818cf8"): string {
  return BRAND_COLORS[slug] ?? fallback;
}

/* ==========================================================================
   CATEGORY ACCENTS  (space-separated RGB so we can add alpha)
   ========================================================================== */

const DEFAULT_ACCENT = "129 140 248";
export const TRIGGER_ACCENT = "245 158 11";

const CATEGORY_ACCENTS: Record<string, string> = {
  trigger: TRIGGER_ACCENT,
  ai: "167 139 250",
  agent: "167 139 250",
  logic: "251 146 60",
  flow: "251 146 60",
  condition: "251 146 60",
  data: "56 189 248",
  transform: "56 189 248",
  http: "45 212 191",
  communication: "52 211 153",
  email: "52 211 153",
  crm: "244 114 182",
  storage: "56 189 248",
  database: "96 165 250",
  payments: "167 139 250",
  security: "244 63 94",
  reliability: "245 158 11",
};

export function resolveAccent(category?: string, isTrigger?: boolean): string {
  if (isTrigger) return TRIGGER_ACCENT;

  const tokens = (category ?? "").toLowerCase().split(/[^a-z]+/);

  for (const token of tokens) {
    if (CATEGORY_ACCENTS[token]) return CATEGORY_ACCENTS[token];
  }

  return DEFAULT_ACCENT;
}

/* ==========================================================================
   BRAND ICON COMPONENT
   Reusable across Workflow Editor, Templates, Integrations, and Modals.
   Optimized 3-tier fallback (theSVG CDN -> local /integrations/ -> vector icon).
   Theme support:
     - "dark": forces mono-dark icons to white silhouette
     - "light": forces mono-dark icons to keep natural dark appearance
     - "auto": dynamically inverts in dark mode via Tailwind (dark:brightness-0 dark:invert)
   ========================================================================== */

export interface BrandIconProps {
  slug?: string | null;
  type?: string;
  label?: string;
  icon?: string;
  name?: string;
  provider?: string;
  theme?: "dark" | "light" | "auto";
  className?: string;
  alt?: string;
  fallbackIcon?: React.ElementType;
  fallbackColor?: string;
  fallbackInitials?: boolean;
}

export function BrandIcon({
  slug: rawSlug,
  type,
  label,
  icon,
  name,
  provider,
  theme = "auto",
  className = "size-5",
  alt = "",
  fallbackIcon,
  fallbackColor,
  fallbackInitials = false,
}: BrandIconProps) {
  const [sourceIndex, setSourceIndex] = React.useState(0);

  const slug = React.useMemo(() => {
    if (rawSlug) return rawSlug;
    return resolveTheSvgSlug(type || provider, label || name, icon);
  }, [rawSlug, type, provider, label, name, icon]);

  React.useEffect(() => {
    setSourceIndex(0);
  }, [slug]);

  const isMonoDark = slug ? MONO_DARK_SLUGS.has(slug) : false;

  const themeFilterClass = isMonoDark
    ? theme === "dark"
      ? "brightness-0 invert"
      : theme === "light"
      ? ""
      : "dark:brightness-0 dark:invert"
    : "";

  // 1. Try remote theSVG CDN
  if (slug && sourceIndex === 0) {
    return (
      <img
        src={theSvgUrl(slug)}
        alt={alt}
        aria-hidden={!alt}
        draggable={false}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        className={cn("object-contain", className, themeFilterClass)}
        onError={() => setSourceIndex(1)}
      />
    );
  }

  // 2. Try local public SVG fallback
  if (slug && sourceIndex === 1) {
    return (
      <img
        src={`/integrations/${slug}.svg`}
        alt={alt}
        aria-hidden={!alt}
        draggable={false}
        loading="lazy"
        decoding="async"
        className={cn("object-contain", className, themeFilterClass)}
        onError={() => setSourceIndex(2)}
      />
    );
  }

  // 3. Capability icon match (e.g. ai, webhook, http, approval, mail)
  const lookupKey = (icon || type || provider || label || "").trim().toLowerCase();
  const capability = CAPABILITY_ICONS[lookupKey];
  if (capability) {
    const CapIcon = capability.icon;
    return (
      <CapIcon
        className={className}
        style={{ color: fallbackColor || capability.color }}
        aria-hidden={!alt}
      />
    );
  }

  // 4. Fallback to vector icon (Lucide)
  if (fallbackIcon || icon) {
    const Fallback = fallbackIcon || resolveIcon(icon);
    return (
      <Fallback
        className={className}
        style={fallbackColor ? { color: fallbackColor } : undefined}
        aria-hidden={!alt}
      />
    );
  }

  // 5. Fallback to initials if requested
  if (fallbackInitials && (name || provider || label || type)) {
    return (
      <div
        className={cn(
          className,
          "flex items-center justify-center rounded font-mono text-[11px] font-semibold select-none",
          "bg-zinc-100 text-zinc-700 dark:bg-white/[0.08] dark:text-white/80",
        )}
        aria-hidden="true"
      >
        {initialsFrom(name || provider || label || type || "?")}
      </div>
    );
  }

  // 6. Generic plug icon
  return <Plug className={className} aria-hidden={!alt} />;
}

/* ==========================================================================
   NODE ICON (Canvas & Palette)
   ========================================================================== */

const SIZES = {
  node: {
    logo: "size-[38px]",
    glyph: "size-[30px]",
  },
  palette: {
    logo: "size-[24px]",
    glyph: "size-[22px]",
  },
} as const;

export function NodeIcon({
  type,
  label,
  icon,
  accent,
  variant = "node",
  theme = "auto",
  className,
}: {
  type?: string;
  label?: string;
  icon?: string;
  accent?: string;
  variant?: keyof typeof SIZES;
  theme?: "dark" | "light" | "auto";
  className?: string;
}) {
  const [sourceIndex, setSourceIndex] = React.useState(0);

  const slug = React.useMemo(
    () => resolveTheSvgSlug(type, label, icon),
    [type, label, icon],
  );

  React.useEffect(() => {
    setSourceIndex(0);
  }, [slug]);

  const size = SIZES[variant];
  const isMonoDark = slug ? MONO_DARK_SLUGS.has(slug) : false;

  const themeFilterClass = isMonoDark
    ? theme === "dark"
      ? "brightness-0 invert"
      : theme === "light"
      ? ""
      : "dark:brightness-0 dark:invert"
    : "";

  let visual: React.ReactNode;

  if (slug && sourceIndex === 0) {
    visual = (
      <img
        src={theSvgUrl(slug)}
        alt=""
        aria-hidden="true"
        draggable={false}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        className={cn(
          "object-contain",
          size.logo,
          variant === "node" &&
            "drop-shadow-[0_1px_2px_rgba(0,0,0,0.12)] dark:drop-shadow-[0_1px_3px_rgba(0,0,0,0.45)]",
          themeFilterClass,
        )}
        onError={() => setSourceIndex(1)}
      />
    );
  } else if (slug && sourceIndex === 1) {
    visual = (
      <img
        src={`/integrations/${slug}.svg`}
        alt=""
        aria-hidden="true"
        draggable={false}
        loading="lazy"
        decoding="async"
        className={cn(
          "object-contain",
          size.logo,
          variant === "node" &&
            "drop-shadow-[0_1px_2px_rgba(0,0,0,0.12)] dark:drop-shadow-[0_1px_3px_rgba(0,0,0,0.45)]",
          themeFilterClass,
        )}
        onError={() => setSourceIndex(2)}
      />
    );
  } else {
    // Check capability icon first (Webhook, AI, HTTP, etc.)
    const lookupKey = (icon || type || label || "").trim().toLowerCase();
    const capability = CAPABILITY_ICONS[lookupKey];
    if (capability) {
      const CapIcon = capability.icon;
      visual = (
        <CapIcon
          className={size.glyph}
          style={{ color: accent ? `rgb(${accent})` : capability.color }}
          strokeWidth={1.75}
          aria-hidden="true"
        />
      );
    } else {
      const FallbackIcon = resolveIcon(icon);
      visual = (
        <FallbackIcon
          className={size.glyph}
          style={accent ? { color: `rgb(${accent})` } : undefined}
          strokeWidth={1.75}
          aria-hidden="true"
        />
      );
    }
  }

  /* Canvas: bare icon, no wrapper */
  if (variant === "node") return visual;

  /* Palette: fixed slot so every row's text lines up */
  return (
    <span
      className={cn(
        "flex size-8 shrink-0 items-center justify-center",
        className,
      )}
    >
      {visual}
    </span>
  );
}

/* ==========================================================================
   PROVIDER ICON COMPONENT
   Standard provider badge icon used in Integrations, Dialogs, Cards
   ========================================================================== */

export interface ProviderIconProps {
  /** Lucide icon name, wire type, or provider slug */
  icon?: string;
  /** Integration wire type, e.g. "github", "postgresql", "slack" */
  provider?: string;
  /** Human-readable provider name, e.g. "GitHub", "PostgreSQL" */
  name?: string;
  className?: string;
  theme?: "dark" | "light" | "auto";
  fallbackInitials?: boolean;
}

export function ProviderIcon({
  icon,
  provider,
  name,
  className = "size-6",
  theme = "auto",
  fallbackInitials = false,
}: ProviderIconProps) {
  return (
    <BrandIcon
      provider={provider}
      name={name}
      icon={icon}
      className={className}
      theme={theme}
      fallbackInitials={fallbackInitials}
    />
  );
}

/* ==========================================================================
   LEGACY / EXPORT COMPATIBILITY DICTIONARY
   ========================================================================== */

export const THESVG_SLUGS: Record<string, string> = {
  // Automation
  n8n: "n8n",
  make: "make",
  zapier: "zapier",
  temporal: "temporal",

  // Developer
  github: "github",
  githubactions: "github-actions",
  "github-actions": "github-actions",
  gitlab: "gitlab",
  vercel: "vercel",
  sentry: "sentry",
  pagerduty: "pagerduty",
  linear: "linear",
  jira: "jira",
  asana: "asana",

  // CRM
  hubspot: "hubspot",
  salesforce: "salesforce",
  pipedrive: "pipedrive",

  // Communication
  slack: "slack",
  discord: "discord",
  teams: "microsoft-teams",
  "microsoft-teams": "microsoft-teams",
  telegram: "telegram",
  twilio: "twilio",

  // Email
  gmail: "gmail",
  sendgrid: "sendgrid",
  resend: "resend",

  // Productivity & Google
  calendar: "google-calendar",
  "google-calendar": "google-calendar",
  notion: "notion",
  "google-sheets": "google-sheets",
  googlesheets: "google-sheets",
  sheets: "google-sheets",

  // Payments
  stripe: "stripe",

  // AI
  openai: "openai",
  anthropic: "anthropic",

  // Cloud / storage
  aws: "aws",
  s3: "amazon-s3",
  "aws-s3": "amazon-s3",
};
