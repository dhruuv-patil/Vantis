import {
  Activity,
  AlertTriangle,
  ShieldAlert,
  Lock,
  KeyRound,
  ScrollText,
  RefreshCcw,
  DatabaseZap,
  Cog,
  GitBranch,
  Boxes,
} from "lucide-react";

export const tabs = [
  {
    label: "Build",
    heading: "Build workflows without the guesswork.",
    copy: "Compose triggers, actions, branches, and services in one visual workflow builder — then ship them with confidence.",
  },
  {
    label: "Monitor",
    heading: "One source of truth for workflow health.",
    copy: "See what is running, what is slowing down, and where reliability is trending across your automation stack.",
  },
  {
    label: "Detect",
    heading: "Catch drift before it spreads.",
    copy: "Vantis learns your baseline and surfaces anomalies while there is still time to act.",
  },
  {
    label: "Diagnose",
    heading: "Turn failures into fixes.",
    copy: "Trace the exact step, timing, and failure context behind every failed execution.",
  },
];
export const faqs = [
  "What is Vantis?",
  "Does Vantis replace my workflow tools?",
  "How quickly can I get started?",
  "Can I export events to our existing stack?",
];
export const faqAnswers = [
  "Vantis is reliability infrastructure for automation — helping teams build, monitor, detect abnormal behavior, and investigate workflow failures.",
  "No — Vantis can build workflows itself, and it can also connect to workflows you already run through supported integrations, webhooks, and HTTP APIs.",
  "Start by building a workflow in Vantis, or send execution events from an existing system through a webhook or HTTP API.",
  "Yes. Vantis supports webhooks and HTTP APIs, with Slack available for operational notifications.",
];
export const integrationGroups = [
  { title: "Workflow tools", icon: Boxes, items: ["n8n", "Zapier", "Make"] },
  {
    title: "Orchestration",
    icon: Cog,
    items: ["Temporal", "Airflow", "Dagster"],
  },
  {
    title: "Alerting",
    icon: AlertTriangle,
    items: ["Slack", "PagerDuty", "Opsgenie"],
  },
  {
    title: "Observability",
    icon: Activity,
    items: ["Datadog", "Grafana", "Honeycomb"],
  },
  {
    title: "Data systems",
    icon: DatabaseZap,
    items: ["Snowflake", "BigQuery", "Postgres"],
  },
  {
    title: "Developer tools",
    icon: GitBranch,
    items: ["GitHub", "Linear", "Vercel"],
  },
];
export const integrationCards = [
  {
    title: "Workflow automation",
    items: [
      ["n8n", "n8n"],
      ["Zapier", "zapier"],
      ["Make", "make"],
    ],
  },
  {
    title: "Orchestration",
    items: [
      ["Temporal", "temporal"],
      ["Airflow", "apache-airflow"],
      ["Dagster", "dagster"],
    ],
  },
  {
    title: "Cloud",
    items: [
      ["AWS", "aws"],
      ["Google Cloud", "google-cloud"],
      ["Azure", "microsoft-azure"],
    ],
  },
  {
    title: "Observability",
    items: [
      ["Datadog", "datadog"],
      ["Grafana", "grafana"],
      ["OpenTelemetry", "opentelemetry"],
    ],
  },
  {
    title: "Alerting & communication",
    items: [
      ["Slack", "slack"],
      ["PagerDuty", "pagerduty"],
      ["Discord", "discord"],
    ],
  },
  {
    title: "Developer tools",
    items: [
      ["GitHub", "github"],
      ["GitLab", "gitlab"],
      ["Vercel", "vercel"],
    ],
  },
  {
    title: "Project management",
    items: [
      ["Linear", "linear"],
      ["Jira", "jira"],
      ["Notion", "notion"],
    ],
  },
  {
    title: "Databases",
    items: [
      ["PostgreSQL", "postgresql"],
      ["Redis", "redis"],
      ["Supabase", "supabase"],
    ],
  },
  {
    title: "Security",
    items: [
      ["Sentry", "sentry"],
      ["Okta", "okta"],
      ["Cloudflare", "cloudflare"],
    ],
  },
  {
    title: "AI",
    items: [
      ["OpenAI", "openai"],
      ["Anthropic", "anthropic"],
      ["Hugging Face", "hugging-face"],
    ],
  },
  {
    title: "Payments",
    items: [
      ["Stripe", "stripe"],
      ["PayPal", "paypal"],
    ],
  },
  {
    title: "Email",
    items: [
      ["SendGrid", "sendgrid"],
      ["Resend", "resend"],
      ["Twilio", "twilio"],
    ],
  },
];

export const securityItems = [
  { title: "Private by default", icon: Lock },
  { title: "Role-based access", icon: KeyRound },
  { title: "Audit-ready", icon: ScrollText },
  { title: "Encrypted everywhere", icon: ShieldAlert },
  { title: "Flexible retention", icon: RefreshCcw },
  { title: "Built to recover", icon: RefreshCcw },
];

/* ---------- small animation helpers ---------- */
