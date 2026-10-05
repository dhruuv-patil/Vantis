/**
 * Vantis landing page — content layer.
 *
 * All copy, integration claims and link targets live here so that:
 *  - marketing/product can update text without touching JSX or CSS
 *  - claims can be audited in one file
 *  - an integration can be promoted from 'soon' → 'live' with a one-word change
 *
 * RULE: never add an integration with status 'live' unless it exists in the codebase.
 */

/* ------------------------------------------------------------------ */
/* Types                                                               */
/* ------------------------------------------------------------------ */

export type IntegrationStatus = "live" | "soon";

export interface Integration {
  id: string;
  name: string;
  status: IntegrationStatus;
  /** simpleicons.org slug. Omit to render `glyph` instead. */
  slug?: string;
  /** Brand colour (hex). */
  color: string;
  /** Fallback glyph when there is no logo. */
  glyph?: string;
  /** Hex (no #) used for the monochrome chips in the Copilot section. */
  chipHex?: string;
}

export interface IntegrationGroup {
  title: string;
  itemIds: readonly string[];
}

export type StepType = "trigger" | "condition" | "action" | "notify";

export interface WorkflowStep {
  type: StepType;
  label: string;
  title: string;
  detail: string;
  icon: string;
  /** Palette hint in the builder demo. */
  hint: string;
  /** Inspector fields in the builder demo: [label, value?] pairs. */
  field1: string;
  field2: string;
  value2: string;
}

export type TraceState = "ok" | "warn" | "fail";

export interface FooterLink {
  label: string;
  /** Set to undefined to hide a link until the page exists. */
  href?: string;
}

/* ------------------------------------------------------------------ */
/* Navigation                                                          */
/* ------------------------------------------------------------------ */

export const NAV = [
  { label: "Builder", href: "#workflow-builder" },
  { label: "Platform", href: "#platform" },
  { label: "Integrations", href: "#integrations" },
  { label: "Security", href: "#security" },
  { label: "FAQs", href: "#faq" },
] as const;

export const ROUTES = { login: "/login", register: "/register" } as const;

export const ANNOUNCEMENT = {
  badge: "NEW",
  text: "Vantis is now in private beta",
} as const;

/* ------------------------------------------------------------------ */
/* Hero                                                                */
/* ------------------------------------------------------------------ */

export const HERO = {
  eyebrow: "Reliability infrastructure for automation",
  headline: ["Vantis", "Reliability infrastructure for automation."],
  paragraph:
    "Build, monitor, and understand your workflows before failures become incidents.",
  primaryCta: "Start building",
  secondaryCta: "See how it works",
  note: "Free during private beta · No credit card required",
} as const;

export const HERO_TABS = [
  {
    label: "Build",
    heading: "Build workflows without losing visibility.",
    copy: "Design triggers, conditions, actions, and branches visually, then test the workflow before it reaches production.",
  },
  {
    label: "Monitor",
    heading: "One source of truth for workflow health.",
    copy: "Track executions, latency, failures, and workflow behavior from a single operational view.",
  },
  {
    label: "Detect",
    heading: "Catch abnormal behavior early.",
    copy: "Compare execution behavior against established baselines and surface meaningful changes before they become larger incidents.",
  },
  {
    label: "Diagnose",
    heading: "Turn failures into investigation paths.",
    copy: "Trace the affected execution, identify the failing step, inspect timing and context, and understand what changed.",
  },
] as const;

/* ------------------------------------------------------------------ */
/* Example workflow (shared by hero Build tab + builder section)       */
/* ------------------------------------------------------------------ */

export const EXAMPLE_WORKFLOW: readonly WorkflowStep[] = [
  {
    type: "trigger",
    label: "Trigger",
    title: "Stripe webhook",
    detail: "order.created",
    icon: "↗",
    hint: "Start a workflow",
    field1: "Event",
    field2: "Endpoint",
    value2: "/hooks/orders",
  },
  {
    type: "condition",
    label: "Condition",
    title: "Order value",
    detail: "amount > $100",
    icon: "◇",
    hint: "Branch on data",
    field1: "Rule",
    field2: "Branch",
    value2: "YES → continue",
  },
  {
    type: "action",
    label: "Action",
    title: "Create fulfillment",
    detail: "POST /fulfillment",
    icon: "→",
    hint: "Call a service",
    field1: "Endpoint",
    field2: "Method",
    value2: "POST",
  },
  {
    type: "notify",
    label: "Notify",
    title: "Post to Slack",
    detail: "#operations",
    icon: "◈",
    hint: "Send an alert",
    field1: "Channel",
    field2: "Severity",
    value2: "Operational",
  },
];

export const EXAMPLE_CONNECTIONS = ["Stripe", "Postgres", "Slack"] as const;

/* ------------------------------------------------------------------ */
/* Monitor section                                                     */
/* ------------------------------------------------------------------ */

export const MONITOR_SECTION = {
  eyebrow: "Monitor",
  heading: "One source of truth for workflow health.",
  paragraph:
    "See what is running, where executions are slowing down, which workflows are degrading, and how reliability changes over time.",
  pills: ["Execution health", "Workflow telemetry", "Reliability trends"],
  link: { label: "See the workflow builder", href: "#workflow-builder" },
  panelKicker: "Example workspace · illustrative data",
  panelTitle: "Workflow operations",
} as const;

export const MONITOR_WORKFLOWS = [
  {
    icon: "↗",
    name: "Stripe → HubSpot",
    sub: "Customer sync",
    runs: "2,481 runs",
    status: "Healthy",
    degraded: false,
  },
  {
    icon: "◈",
    name: "Lead enrichment",
    sub: "AI enrichment pipeline",
    runs: "842 runs",
    status: "Degraded",
    degraded: true,
  },
  {
    icon: "⌁",
    name: "Slack incident alerts",
    sub: "Production notifications",
    runs: "3,104 runs",
    status: "Healthy",
    degraded: false,
  },
] as const;

/* ------------------------------------------------------------------ */
/* Detect / Diagnose / Resolve                                         */
/* ------------------------------------------------------------------ */

export const DIAGNOSE_SECTION = {
  eyebrow: "Detect · Diagnose · Resolve",
  heading: "Know exactly where a workflow went wrong.",
  paragraph:
    "Vantis connects execution history, node-level telemetry, timing, failures, and reliability signals so your team can investigate incidents without jumping between disconnected tools.",
  pills: ["Execution traces", "Node telemetry", "Failure context"],
} as const;

export const TRACE_ROWS: readonly {
  state: TraceState;
  title: string;
  meta: string;
  time: string;
  selected?: boolean;
}[] = [
  {
    state: "ok",
    title: "Webhook received",
    meta: "POST /orders · 12:41:08.012",
    time: "84ms",
  },
  {
    state: "ok",
    title: "Validate customer",
    meta: "customer-service · 12:41:08.096",
    time: "162ms",
  },
  {
    state: "warn",
    title: "Sync to warehouse",
    meta: "warehouse-service · 12:41:08.258",
    time: "29.7s",
    selected: true,
  },
  {
    state: "fail",
    title: "Customer confirmation",
    meta: "skipped after timeout",
    time: "—",
  },
];

export const FAILURE_CARD = {
  title: "Failure details",
  status: "Needs attention",
  code: `error: TimeoutError\nstep: sync_customer_to_warehouse\nduration: 30.04s · retry: 2/3\n\nSuggested: inspect warehouse connection pool`,
  eyebrow: "Diagnose",
  heading: "Make the invisible failure visible.",
  paragraph:
    "Inspect the execution, step-level logs, and failure context without jumping between five different tools.",
  pills: ["Structured traces", "Execution context", "Impact analysis"],
} as const;

/* ------------------------------------------------------------------ */
/* "Hard to trust" section                                             */
/* ------------------------------------------------------------------ */

export const TRUST_PROBLEM = {
  eyebrow: "For the workflows between tools and teams",
  heading: "Where automated workflows become hard to trust.",
  paragraph:
    "Automations often fail between systems, services, APIs, and teams. Vantis watches workflow executions, establishes behavioral context, detects abnormal changes, and gives your team the evidence needed to investigate.",
  consoleTitle: "WORKFLOW RELIABILITY",
  consoleEnv: "example",
  consoleTime: "EXAMPLE · ILLUSTRATIVE DATA",
  workflowName: "order_to_fulfillment",
  insight: {
    label: "VANTIS SIGNAL",
    before: "Inventory sync latency is ",
    strong: "14× above baseline",
    after:
      ". The increase started 18 minutes after the latest warehouse deployment.",
    cta: "Inspect execution",
    href: "#get-started",
  },
  footer: ["Monitor", "Detect", "Diagnose", "Resolve"],
  footerRight: "18 example workflows",
} as const;

export const PROBLEM_NODES = [
  { state: "ok", icon: "↗", title: "Order webhook", meta: "42ms" },
  { state: "ok", icon: "✓", title: "Validate order", meta: "118ms" },
  { state: "warn", icon: "!", title: "Inventory sync", meta: "8.4s" },
  { state: "fail", icon: "×", title: "Fulfillment", meta: "timed out" },
] as const;

export const PROBLEM_STATS = [
  { label: "RELIABILITY", value: "96.4%", sub: "↓ 2.1% today" },
  { label: "FAILED RUNS", value: "37", sub: "of 1,284 executions" },
  { label: "P95 LATENCY", value: "2.81s", sub: "+34% vs baseline" },
] as const;

/* ------------------------------------------------------------------ */
/* Reliability layer                                                   */
/* ------------------------------------------------------------------ */

export const LAYER_SECTION = {
  eyebrow: "One reliability layer",
  heading: ["One reliability layer", "for every workflow."],
  paragraph:
    "Build workflows in Vantis or connect workflows you already run. Vantis brings their execution signals into one place for monitoring, anomaly detection, investigation, and operational response.",
  sourcesLabel: "WORKFLOW SOURCES",
  sources: ["Vantis", "n8n", "Webhooks", "HTTP APIs", "Custom jobs"],
  brand: "VANTIS",
  brandSub: "RELIABILITY LAYER",
  capabilities: ["Build", "Run", "Monitor", "Resolve"],
  targetsLabel: "PRODUCTION SYSTEMS",
  targets: ["APIs", "Postgres", "AWS", "Services"],
} as const;

/* ------------------------------------------------------------------ */
/* Incident journey + outcomes                                         */
/* ------------------------------------------------------------------ */

export const JOURNEY = {
  eyebrow: "From signal to resolution",
  heading: "One incident. The whole story.",
  paragraph:
    "Vantis connects the scattered pieces of a workflow failure into one investigation path.",
  steps: [
    {
      n: "01",
      label: "SIGNAL",
      title: "Workflow behavior changes",
      text: "Vantis observes a meaningful change in execution behavior.",
    },
    {
      n: "02",
      label: "DETECT",
      title: "Execution breaks its baseline",
      text: "Latency, errors, or execution behavior move outside the expected range.",
    },
    {
      n: "03",
      label: "DIAGNOSE",
      title: "Identify the affected step",
      text: "Trace the execution and inspect the timing, dependencies, retries, and available context.",
    },
    {
      n: "04",
      label: "RESOLVE",
      title: "Turn evidence into action",
      text: "Give the team a clear incident context with the affected workflow, execution, signals, and investigation findings.",
    },
  ],
} as const;

export const OUTCOMES = {
  eyebrow: "What changes with Vantis",
  heading: ["Less time hunting.", "More time fixing."],
  paragraph:
    "Reliability becomes something your team can operate, not something they discover after the workflow has already failed.",
  cards: [
    {
      n: "01",
      title: "Every execution",
      text: "Traceable from trigger to final action.",
    },
    {
      n: "02",
      title: "Every anomaly",
      text: "Compared against observed workflow behavior.",
    },
    {
      n: "03",
      title: "Every failure",
      text: "Connected to execution and available telemetry context.",
    },
    {
      n: "04",
      title: "Every incident",
      text: "Ready for investigation and team handoff.",
    },
  ],
} as const;

/* ------------------------------------------------------------------ */
/* Integrations                                                        */
/* ------------------------------------------------------------------ */

/**
 * Registry of every integration the page may mention.
 * Only these are claimed. Zapier, Make, Temporal, Airflow, Dagster, PagerDuty,
 * Opsgenie, Datadog, Grafana, Honeycomb, Snowflake, BigQuery, GitHub, Linear,
 * Vercel, Discord and Microsoft Teams were removed: no confirmed implementation.
 *
 * TODO(verify): confirm n8n and PostgreSQL against the provider registry.
 */
export const INTEGRATIONS: Record<string, Integration> = {
  n8n: {
    id: "n8n",
    name: "n8n",
    status: "live",
    slug: "n8n",
    color: "#EA4B71",
    chipHex: "ffffff",
  },
  webhooks: {
    id: "webhooks",
    name: "Webhooks",
    status: "live",
    color: "#F59E0B",
    glyph: "⌁",
  },
  "http-apis": {
    id: "http-apis",
    name: "HTTP APIs",
    status: "live",
    color: "#22C55E",
    glyph: "↗",
  },
  slack: {
    id: "slack",
    name: "Slack",
    status: "live",
    slug: "slack",
    color: "#36C5F0",
    chipHex: "36C5F0",
  },
  postgresql: {
    id: "postgresql",
    name: "PostgreSQL",
    status: "live",
    slug: "postgresql",
    color: "#4169E1",
    chipHex: "ffffff",
  },
  "webhook-triggers": {
    id: "webhook-triggers",
    name: "Custom webhook triggers",
    status: "live",
    color: "#F59E0B",
    glyph: "⌁",
  },
  "api-actions": {
    id: "api-actions",
    name: "HTTP API actions",
    status: "live",
    color: "#22C55E",
    glyph: "↗",
  },
};

export const INTEGRATION_GROUPS: readonly IntegrationGroup[] = [
  { title: "Workflow connectivity", itemIds: ["n8n", "webhooks", "http-apis"] },
  { title: "Operational systems", itemIds: ["slack", "postgresql"] },
  { title: "Extensibility", itemIds: ["webhook-triggers", "api-actions"] },
];

export const INTEGRATIONS_SECTION = {
  eyebrow: "Connect without changing your stack",
  heading: "Fits into the stack you already run.",
  paragraph:
    "Build workflows in Vantis or connect the ones you already run. Start with the integrations Vantis supports today, or connect anything through webhooks and HTTP APIs.",
  custom: "+ custom webhook triggers and HTTP API action blocks",
  soonLabel: "Coming soon",
} as const;

/* ------------------------------------------------------------------ */
/* Builder + Copilot                                                   */
/* ------------------------------------------------------------------ */

export const BUILDER_SECTION = {
  eyebrow: "Workflow builder",
  heading: ["Build the workflow.", "Then make it reliable."],
  paragraph:
    "Design triggers, conditions, actions, and branches visually, validate the workflow before production, and keep every execution observable.",
  contextLabel: "Example workflow · order_to_fulfillment",
} as const;

export const COPILOT = {
  eyebrow: "Vantis AI",
  heading: ["Investigate with context.", "Act with confidence."],
  paragraph:
    "Ask Vantis to investigate workflow failures, compare recent executions, inspect reliability signals, and prepare an incident summary from the context already captured by the platform.",
  prompt:
    "Checkout workflows are timing out after the latest deployment. Find the failing dependency, compare it with recent executions, and prepare an incident summary for the team.",
  maxChars: 1000,
  context: "example / checkout",
  systemIds: ["n8n", "postgresql", "slack"],
  scope: ["18 workflows", "last 24h"],
  actions: [
    {
      n: "01",
      title: "Trace the execution path",
      text: "Find the first abnormal step and its upstream dependencies.",
    },
    {
      n: "02",
      title: "Compare against baseline",
      text: "Check latency, error rate, and recent deployment changes.",
    },
    {
      n: "03",
      title: "Prepare the incident handoff",
      text: "Summarize evidence with links to traces and affected runs.",
    },
  ],
} as const;

/* ------------------------------------------------------------------ */
/* Security                                                            */
/* ------------------------------------------------------------------ */

export const SECURITY = {
  eyebrow: "Trust & security",
  heading: "Built for production workflows.",
  paragraph:
    "Reliability depends on controlled access, traceable activity, and predictable execution data.",
  // NOTE: do not add encryption, tenancy, retention or compliance claims until verified.
  items: [
    {
      n: "01",
      title: "Access control",
      text: "Role-based access and controlled permissions keep workflow operations scoped to the right users.",
    },
    {
      n: "02",
      title: "Execution visibility",
      text: "Workflow executions and operational events remain traceable for investigation and troubleshooting.",
    },
    {
      n: "03",
      title: "Operational control",
      text: "Clear workflow ownership, audit context, and controlled integrations help teams operate reliably.",
    },
  ],
  footer: ["Scoped permissions", "Execution audit trail", "Role-based access"],
} as const;

/* ------------------------------------------------------------------ */
/* FAQ                                                                 */
/* ------------------------------------------------------------------ */

export const FAQS = [
  {
    q: "What is Vantis?",
    a: "Vantis is reliability infrastructure for automation — helping teams build, monitor, detect abnormal behavior, and investigate workflow failures.",
  },
  {
    q: "Does Vantis replace my workflow tools?",
    a: "No. You can build workflows in Vantis, or keep running workflows in the tools you already use and connect their execution signals to Vantis.",
  },
  {
    q: "How does Vantis monitor existing workflows?",
    a: "Existing workflows report execution events to Vantis through webhooks or HTTP APIs. Vantis then tracks executions, timing, and failures alongside the workflows you build natively.",
  },
  {
    q: "How do I connect a workflow to Vantis?",
    a: "Send execution events from your workflow using a webhook or an HTTP API request, or connect a supported workflow tool such as n8n. Workflows built in Vantis are observable from their first run.",
  },
  {
    q: "Does Vantis support webhooks and HTTP APIs?",
    a: "Yes. Vantis supports custom webhook triggers and HTTP API action blocks, and Slack is available for operational notifications.",
  },
  {
    q: "What does Vantis detect?",
    a: "Vantis compares latency, error rates, and execution behavior against observed baselines and surfaces meaningful changes, along with failed executions and their context.",
  },
  {
    q: "Does Vantis use AI?",
    a: "Vantis AI includes an investigation assistant. It uses context already captured by the platform to help you investigate failures, compare executions, and prepare an incident summary. It is a capability within the reliability platform, not an autonomous remediation system.",
  },
  {
    q: "How quickly can I get started?",
    a: "Start by building a workflow in Vantis, or send execution events from an existing system through a webhook or HTTP API. Vantis is free during private beta.",
  },
] as const;

/* ------------------------------------------------------------------ */
/* Final CTA                                                           */
/* ------------------------------------------------------------------ */

export const FINAL_CTA = {
  eyebrow: "Get started with Vantis",
  heading: ["Build every workflow.", "Trust every run."],
  paragraph:
    "Build workflows in Vantis or connect the systems you already use. Capture execution context, detect reliability issues, and investigate failures from one place.",
  primary: "Start building",
  secondary: "Explore the platform",
  notes: [
    "Connect your stack",
    "Trace every execution",
    "Resolve with context",
  ],
} as const;

/* ------------------------------------------------------------------ */
/* Footer                                                              */
/* ------------------------------------------------------------------ */

export const FOOTER = {
  tagline:
    "Reliability infrastructure for automation.",
  operational: "All systems operational",
  groups: [
    {
      title: "Platform",
      links: [
        { label: "Overview", href: "#platform" },
        { label: "Workflow Builder", href: "#workflow-builder" },
        { label: "Monitoring", href: "#platform" },
        { label: "Reliability", href: "#reliability-layer" },
      ],
    },
    {
      title: "Resources",
      links: [
        // TODO(verify): confirm these routes exist. Set href to undefined to hide.
        { label: "Documentation", href: "/docs" },
        { label: "Guides", href: "/guides" },
        { label: "FAQ", href: "#faq" },
      ],
    },
    {
      title: "Company",
      links: [
        { label: "About", href: "/about" },
        { label: "Contact", href: "/contact" },
      ],
    },
    {
      title: "Legal",
      links: [
        { label: "Privacy", href: "/privacy" },
        { label: "Terms", href: "/terms" },
      ],
    },
  ] as readonly { title: string; links: readonly FooterLink[] }[],
} as const;
