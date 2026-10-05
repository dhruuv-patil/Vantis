-- Vantis — AI Usage Events
-- Records billable/quota-consuming AI operations per workspace.

CREATE TABLE IF NOT EXISTS ai_usage_events (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_type      varchar(64)  NOT NULL,   -- 'AI_WORKFLOW_GENERATION', 'AI_INVESTIGATION'
    quantity        integer      NOT NULL DEFAULT 1,
    metadata        text,
    created_at      timestamptz  NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_ai_usage_events_org_created ON ai_usage_events (organization_id, created_at);
CREATE INDEX IF NOT EXISTS ix_ai_usage_events_org_type ON ai_usage_events (organization_id, event_type);
