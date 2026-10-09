/**
 * Client-side provider metadata registry.
 *
 * Brand integrations use their provider wire type as the icon identifier.
 * ProviderIcon resolves these identifiers to SVG files located in:
 *
 *   /public/integrations/
 *
 * Example:
 *
 *   github -> /integrations/github.svg
 *   slack  -> /integrations/slack.svg
 *   stripe -> /integrations/stripe.svg
 *
 * Generic/non-brand integrations continue using Lucide icons.
 */

import type { ProviderCategory } from "@/types";

export interface ProviderMeta {
  /**
   * Brand SVG identifier or Lucide icon name.
   */
  icon: string;

  category: ProviderCategory;

  /**
   * Official dashboard or developer docs URL where the user gets their API credentials.
   */
  docsUrl: string;

  /**
   * Friendly label for the link.
   */
  docsLabel: string;

  /**
   * Clear step-by-step instructions on where to find the key and how to connect.
   */
  setupGuide: string;
}

/**
 * Metadata for every registered backend provider.
 *
 * Keys must match the wire type returned by IntegrationType.wire(), with
 * normalized fallbacks for both underscore and hyphen notation.
 */
export const PROVIDER_META: Record<string, ProviderMeta> = {
  // ============================================================
  // WORKFLOW AUTOMATION
  // ============================================================

  n8n: {
    icon: "n8n",
    category: "AUTOMATION",
    docsUrl: "https://docs.n8n.io/api/",
    docsLabel: "n8n API Documentation",
    setupGuide: "1. Open your n8n workspace and go to Settings → n8n API.\n2. Click 'Create an API key' and copy the token.\n3. Paste your instance Base URL and API Key below.",
  },

  make: {
    icon: "make",
    category: "AUTOMATION",
    docsUrl: "https://www.make.com/en/help/tools/api",
    docsLabel: "Make API Token Guide",
    setupGuide: "1. Log into your Make account.\n2. Navigate to Profile / Organization → API.\n3. Create an API token, select your region (e.g. eu1 or us1), and enter it below.",
  },

  zapier: {
    icon: "zapier",
    category: "AUTOMATION",
    docsUrl: "https://zapier.com/help/create/code-webhooks/trigger-zaps-from-webhooks",
    docsLabel: "Zapier Catch Hook Guide",
    setupGuide: "1. In Zapier, create a new Zap with trigger 'Webhooks by Zapier' → 'Catch Hook'.\n2. Copy the generated Webhook URL from the trigger setup.\n3. Paste the Webhook URL below to connect.",
  },

  temporal: {
    icon: "temporal",
    category: "AUTOMATION",
    docsUrl: "https://docs.temporal.io/cloud/api-keys",
    docsLabel: "Temporal Cloud API Keys",
    setupGuide: "1. Log in to Temporal Cloud and go to Settings → API Keys.\n2. Generate an API Key with namespace access.\n3. Enter your Temporal namespace and API Key below.",
  },

  // ============================================================
  // DEVELOPER / SOURCE CONTROL
  // ============================================================

  github: {
    icon: "github",
    category: "DEVELOPER",
    docsUrl: "https://github.com/settings/tokens",
    docsLabel: "GitHub Personal Access Tokens",
    setupGuide: "1. Go to GitHub Settings → Developer Settings → Personal Access Tokens.\n2. Generate a token with 'repo' and 'workflow' (or 'actions:read') scopes.\n3. Enter your repository owner, repository name, and Personal Access Token.",
  },

  gitlab: {
    icon: "gitlab",
    category: "DEVELOPER",
    docsUrl: "https://gitlab.com/-/user_settings/personal_access_tokens",
    docsLabel: "GitLab Access Tokens",
    setupGuide: "1. In GitLab, navigate to Preferences / User Settings → Access Tokens.\n2. Create a Personal Access Token with 'api' or 'read_api' scopes.\n3. Enter your GitLab instance URL and the Personal Access Token.",
  },

  vercel: {
    icon: "vercel",
    category: "DEVELOPER",
    docsUrl: "https://vercel.com/account/tokens",
    docsLabel: "Vercel Personal Tokens",
    setupGuide: "1. Go to Vercel Account Settings → Tokens.\n2. Click 'Create Token' with desired team and project access.\n3. Paste the generated token below.",
  },

  sentry: {
    icon: "sentry",
    category: "DEVELOPER",
    docsUrl: "https://sentry.io/settings/account/api/auth-tokens/",
    docsLabel: "Sentry Auth Tokens",
    setupGuide: "1. In Sentry, go to User Settings → Account → API → Auth Tokens.\n2. Click 'Create New Token' with project and issue read permissions.\n3. Paste the generated token below.",
  },

  pagerduty: {
    icon: "pagerduty",
    category: "DEVELOPER",
    docsUrl: "https://support.pagerduty.com/docs/api-access-keys",
    docsLabel: "PagerDuty API Access Keys",
    setupGuide: "1. In PagerDuty, navigate to Integrations → API Access Keys.\n2. Click 'Create New Key'.\n3. Copy the generated key and enter it below.",
  },

  linear: {
    icon: "linear",
    category: "DEVELOPER",
    docsUrl: "https://linear.app/settings/api",
    docsLabel: "Linear Personal API Keys",
    setupGuide: "1. In Linear, navigate to Settings → Account → Security & API.\n2. Under Personal API Keys, click 'Create Key'.\n3. Copy the API key and paste it below.",
  },

  jira: {
    icon: "jira",
    category: "DEVELOPER",
    docsUrl: "https://id.atlassian.com/manage-profile/security/api-tokens",
    docsLabel: "Atlassian API Tokens",
    setupGuide: "1. Go to your Atlassian Account Security → API tokens.\n2. Click 'Create API token' and copy it.\n3. Enter your Atlassian site URL (e.g. https://yourorg.atlassian.net), login email, and API token.",
  },

  asana: {
    icon: "asana",
    category: "PROJECT_MANAGEMENT",
    docsUrl: "https://app.asana.com/0/my-apps",
    docsLabel: "Asana Developer Console",
    setupGuide: "1. Go to Asana Developer App Console.\n2. Under Personal access tokens, click 'New access token'.\n3. Copy the token and paste it below.",
  },

  // ============================================================
  // CRM / SALES
  // ============================================================

  hubspot: {
    icon: "hubspot",
    category: "CRM",
    docsUrl: "https://app.hubspot.com/",
    docsLabel: "HubSpot Private Apps",
    setupGuide: "1. In HubSpot, navigate to Settings → Integrations → Private Apps.\n2. Click 'Create a private app' and configure CRM scopes (e.g. crm.objects.contacts).\n3. Click 'Show token' and paste the Private App Access Token below.",
  },

  salesforce: {
    icon: "salesforce",
    category: "CRM",
    docsUrl: "https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_tokens_scopes.htm",
    docsLabel: "Salesforce Connected Apps Guide",
    setupGuide: "1. In Salesforce Setup, go to App Manager → New Connected App.\n2. Enable OAuth settings and obtain Consumer Key (Client ID) and Consumer Secret.\n3. Enter your Salesforce instance URL, Client ID, and Client Secret.",
  },

  pipedrive: {
    icon: "pipedrive",
    category: "CRM",
    docsUrl: "https://app.pipedrive.com/settings/personal-preferences/api",
    docsLabel: "Pipedrive API Settings",
    setupGuide: "1. In Pipedrive, open Settings → Personal preferences → API.\n2. Copy your Personal API token and paste it below.",
  },

  // ============================================================
  // COMMUNICATION
  // ============================================================

  slack: {
    icon: "slack",
    category: "COMMUNICATION",
    docsUrl: "https://api.slack.com/messaging/webhooks",
    docsLabel: "Slack Incoming Webhooks Guide",
    setupGuide: "1. Go to the Slack API app portal and create or select an app.\n2. Enable 'Incoming Webhooks' and click 'Add New Webhook to Workspace'.\n3. Choose your target channel, authorize, and copy the Webhook URL.",
  },

  discord: {
    icon: "discord",
    category: "COMMUNICATION",
    docsUrl: "https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks",
    docsLabel: "Discord Webhooks Guide",
    setupGuide: "1. In Discord, go to Server Settings or Channel Settings → Integrations → Webhooks.\n2. Click 'New Webhook' and select your channel.\n3. Click 'Copy Webhook URL' and paste it below.",
  },

  teams: {
    icon: "teams",
    category: "COMMUNICATION",
    docsUrl: "https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook",
    docsLabel: "Microsoft Teams Webhooks Guide",
    setupGuide: "1. In Microsoft Teams, go to your channel → More options (...) → Workflows or Connectors.\n2. Search for 'Post to a channel when a webhook request is received' (or Incoming Webhook).\n3. Copy the generated Webhook URL and paste it below.",
  },

  telegram: {
    icon: "telegram",
    category: "COMMUNICATION",
    docsUrl: "https://core.telegram.org/bots/tutorial",
    docsLabel: "Telegram Bot Tutorial & @BotFather",
    setupGuide: "1. Open Telegram and search for the official '@BotFather' account.\n2. Send `/newbot` and follow the prompts to choose a name and username.\n3. Copy the HTTP API Bot Token provided, and enter an optional Default Chat ID.",
  },

  twilio: {
    icon: "twilio",
    category: "COMMUNICATION",
    docsUrl: "https://console.twilio.com/",
    docsLabel: "Twilio Console",
    setupGuide: "1. Open your Twilio Console Dashboard.\n2. Under 'Account Info', copy your Account SID and Auth Token.\n3. Enter your active Twilio phone number in E.164 format (e.g. +15551234567).",
  },

  // ============================================================
  // EMAIL
  // ============================================================

  email: {
    icon: "Mail",
    category: "EMAIL",
    docsUrl: "https://support.google.com/a/answer/176600",
    docsLabel: "SMTP Configuration & App Passwords",
    setupGuide: "1. Use your mail provider's SMTP relay settings.\n2. For Gmail: Enable 2FA in Google Account Security and generate an App Password.\n3. For Outlook: Host is smtp.office365.com on port 587 with STARTTLS.",
  },

  smtp: {
    icon: "Mail",
    category: "EMAIL",
    docsUrl: "https://support.google.com/a/answer/176600",
    docsLabel: "SMTP Configuration & App Passwords",
    setupGuide: "1. Use your mail provider's SMTP relay settings.\n2. For Gmail: Enable 2FA in Google Account Security and generate an App Password.\n3. For Outlook: Host is smtp.office365.com on port 587 with STARTTLS.",
  },

  sendgrid: {
    icon: "sendgrid",
    category: "EMAIL",
    docsUrl: "https://app.sendgrid.com/settings/api_keys",
    docsLabel: "SendGrid API Keys",
    setupGuide: "1. In SendGrid, go to Settings → API Keys.\n2. Click 'Create API Key' with Mail Send or Full Access permissions.\n3. Enter your verified Sender Email address below.",
  },

  resend: {
    icon: "resend",
    category: "EMAIL",
    docsUrl: "https://resend.com/api-keys",
    docsLabel: "Resend API Keys",
    setupGuide: "1. Open Resend Dashboard → API Keys.\n2. Click 'Create API Key' with Sending access.\n3. Enter your verified domain sender email (or onboarding@resend.dev for test mode).",
  },

  // ============================================================
  // GENERIC / WEBHOOKS
  // ============================================================

  webhook: {
    icon: "Webhook",
    category: "UNIVERSAL",
    docsUrl: "https://en.wikipedia.org/wiki/Webhook",
    docsLabel: "Webhook Integration Guide",
    setupGuide: "1. Enter your HTTPS destination URL.\n2. Optionally configure custom Authorization headers or Bearer tokens.\n3. Provide an HMAC Secret if you wish to verify signed payloads.",
  },

  "rest-api": {
    icon: "Globe",
    category: "UNIVERSAL",
    docsUrl: "https://swagger.io/docs/specification/authentication/",
    docsLabel: "REST API Authentication Guide",
    setupGuide: "1. Provide your API service Base URL.\n2. Set Header Name (defaults to 'Authorization') and API Token or Bearer key.\n3. Optionally provide a health check endpoint (e.g. /health) to verify connectivity.",
  },

  rest_api: {
    icon: "Globe",
    category: "UNIVERSAL",
    docsUrl: "https://swagger.io/docs/specification/authentication/",
    docsLabel: "REST API Authentication Guide",
    setupGuide: "1. Provide your API service Base URL.\n2. Set Header Name (defaults to 'Authorization') and API Token or Bearer key.\n3. Optionally provide a health check endpoint (e.g. /health) to verify connectivity.",
  },

  custom: {
    icon: "Globe",
    category: "UNIVERSAL",
    docsUrl: "https://swagger.io/docs/specification/authentication/",
    docsLabel: "Custom Integration Guide",
    setupGuide: "1. Enter the target Base URL and authentication header/credentials.\n2. Save to use in custom workflow nodes.",
  },

  // ============================================================
  // CLOUD / STORAGE
  // ============================================================

  aws: {
    icon: "aws",
    category: "CLOUD",
    docsUrl: "https://console.aws.amazon.com/iam/",
    docsLabel: "AWS IAM Security Credentials",
    setupGuide: "1. In AWS Management Console, open IAM → Users → Security credentials.\n2. Under Access keys, click 'Create access key'.\n3. Enter your Region, Access Key ID, and Secret Access Key below.",
  },

  s3: {
    icon: "s3",
    category: "STORAGE",
    docsUrl: "https://docs.aws.amazon.com/AmazonS3/latest/userguide/",
    docsLabel: "AWS S3 Documentation",
    setupGuide: "1. In AWS IAM, create credentials with S3 read/write permissions.\n2. Enter Region (e.g. us-east-1), Access Key, and Secret Key below.",
  },

  // ============================================================
  // PRODUCTIVITY
  // ============================================================

  notion: {
    icon: "notion",
    category: "PRODUCTIVITY",
    docsUrl: "https://www.notion.so/my-integrations",
    docsLabel: "Notion Integrations Portal",
    setupGuide: "1. Go to Notion's 'My integrations' portal.\n2. Click '+ New integration', select your workspace, and save.\n3. Copy the Internal Integration Secret and make sure to invite the integration to target pages/databases.",
  },

  "google-sheets": {
    icon: "google-sheets",
    category: "PRODUCTIVITY",
    docsUrl: "https://console.cloud.google.com/apis/credentials",
    docsLabel: "Google Cloud Credentials",
    setupGuide: "1. In Google Cloud Console, enable Google Sheets API.\n2. Go to Credentials and create an OAuth 2.0 Client ID or Service Account.\n3. Provide the access token or credentials below.",
  },

  google_sheets: {
    icon: "google-sheets",
    category: "PRODUCTIVITY",
    docsUrl: "https://console.cloud.google.com/apis/credentials",
    docsLabel: "Google Cloud Credentials",
    setupGuide: "1. In Google Cloud Console, enable Google Sheets API.\n2. Go to Credentials and create an OAuth 2.0 Client ID or Service Account.\n3. Provide the access token or credentials below.",
  },

  // ============================================================
  // PAYMENTS
  // ============================================================

  stripe: {
    icon: "stripe",
    category: "PAYMENTS",
    docsUrl: "https://dashboard.stripe.com/apikeys",
    docsLabel: "Stripe API Keys",
    setupGuide: "1. Log into your Stripe Dashboard and toggle Test or Live mode.\n2. Go to Developers → API keys.\n3. Copy your Secret key ('sk_live_...' or 'sk_test_...') and paste it below.",
  },

  // ============================================================
  // AI / LLM
  // ============================================================

  anthropic: {
    icon: "anthropic",
    category: "AI",
    docsUrl: "https://console.anthropic.com/settings/keys",
    docsLabel: "Anthropic Console API Keys",
    setupGuide: "1. Log in to Anthropic Console.\n2. Go to Settings → API Keys and click 'Create Key'.\n3. Copy your API key ('sk-ant-...') and paste it below.",
  },

  openai: {
    icon: "openai",
    category: "AI",
    docsUrl: "https://platform.openai.com/api-keys",
    docsLabel: "OpenAI API Keys",
    setupGuide: "1. Open OpenAI Platform and go to API keys.\n2. Click '+ Create new secret key' with model permissions.\n3. Copy the key ('sk-...') and paste it below.",
  },
};

/**
 * Returns the icon identifier for a provider type.
 *
 * Brand providers return their SVG identifier.
 * Unknown providers fall back to the generic Lucide Plug icon.
 */
export function getProviderIcon(type: string): string {
  const normalized = type.replace(/-/g, "_");
  return (
    PROVIDER_META[type]?.icon ??
    PROVIDER_META[normalized]?.icon ??
    "Plug"
  );
}

/**
 * Returns the category for a provider type.
 *
 * Unknown providers fall back to UNIVERSAL.
 */
export function getProviderCategory(
  type: string,
): ProviderCategory {
  const normalized = type.replace(/-/g, "_");
  return (
    PROVIDER_META[type]?.category ??
    PROVIDER_META[normalized]?.category ??
    "UNIVERSAL"
  );
}

/**
 * Returns the official documentation or API portal URL for a provider.
 */
export function getProviderDocsUrl(type: string): string {
  const normalized = type.replace(/-/g, "_");
  return (
    PROVIDER_META[type]?.docsUrl ??
    PROVIDER_META[normalized]?.docsUrl ??
    "https://docs.flowforge.ai/integrations"
  );
}

/**
 * Returns a friendly label for the provider documentation link.
 */
export function getProviderDocsLabel(type: string): string {
  const normalized = type.replace(/-/g, "_");
  return (
    PROVIDER_META[type]?.docsLabel ??
    PROVIDER_META[normalized]?.docsLabel ??
    "API Credentials & Documentation"
  );
}

/**
 * Returns step-by-step connection instructions for a provider.
 */
export function getProviderSetupGuide(type: string): string {
  const normalized = type.replace(/-/g, "_");
  return (
    PROVIDER_META[type]?.setupGuide ??
    PROVIDER_META[normalized]?.setupGuide ??
    "1. Obtain your API key or webhook credentials from your service provider.\n2. Enter the required credentials in the form fields below.\n3. Click Connect to verify and save the integration."
  );
}