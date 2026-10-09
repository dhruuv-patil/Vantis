package com.flowops.integration.provider;

/**
 * Stable wire identifiers for existing integrations. An enum entry is not a
 * capability claim: only registered provider beans appear in the catalog.
 */
public enum IntegrationType {
    SLACK("slack"),
    EMAIL("email"),
    DISCORD("discord"),
    TEAMS("teams"),
    WEBHOOK("webhook"),
    N8N("n8n"),
    MAKE("make"),
    ZAPIER("zapier"),
    GITHUB("github"),
    TEMPORAL("temporal"),
    REST_API("rest_api"),
    TELEGRAM("telegram"),
    TWILIO("twilio"),
    SENDGRID("sendgrid"),
    RESEND("resend"),
    HUBSPOT("hubspot"),
    STRIPE("stripe"),
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    LINEAR("linear"),
    JIRA("jira"),
    SENTRY("sentry"),
    PAGERDUTY("pagerduty"),
    GITLAB("gitlab"),
    BITBUCKET("bitbucket"),
    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    MONGODB("mongodb"),
    REDIS("redis"),
    AWS("aws"),
    GCP("gcp"),
    AZURE("azure"),
    CLOUDFLARE("cloudflare"),
    VERCEL("vercel"),
    DIGITALOCEAN("digitalocean"),
    S3("s3"),
    GCS("gcs"),
    DRIVE("drive"),
    DROPBOX("dropbox"),
    ONEDRIVE("onedrive"),
    BOX("box"),
    SALESFORCE("salesforce"),
    PIPEDRIVE("pipedrive"),
    ZOHO_CRM("zoho_crm"),
    NOTION("notion"),
    ASANA("asana"),
    TRELLO("trello"),
    CLICKUP("clickup"),
    MONDAY("monday"),
    GOOGLE_SHEETS("google_sheets"),
    GOOGLE_DOCS("google_docs"),
    GOOGLE_CALENDAR("google_calendar"),
    AIRTABLE("airtable"),
    CUSTOM("custom");

    private final String wire;

    IntegrationType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static IntegrationType fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        String trimmed = wire.trim();
        String normalized = trimmed.replace('-', '_');
        for (IntegrationType type : values()) {
            if (type.wire.equalsIgnoreCase(trimmed) || type.wire.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        if ("smtp".equalsIgnoreCase(trimmed)) {
            return EMAIL;
        }
        return null;
    }
}
