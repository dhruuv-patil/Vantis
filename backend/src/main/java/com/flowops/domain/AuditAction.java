package com.flowops.domain;

/**
 * The closed set of security-relevant actions recorded in {@code audit_logs}.
 *
 * <p>Stored as the enum name in a {@code varchar(64)} column, so names are part of the
 * persisted contract: rename one only with a migration. The frontend mirrors this list
 * as a filter; new members must be added there too.
 *
 * <p>Each constant carries a {@code targetType} — the kind of thing acted upon — so the
 * viewer can group without parsing the summary sentence.
 */
public enum AuditAction {
    MEMBER_ROLE_CHANGED("member"),
    MEMBER_REMOVED("member"),
    MEMBER_INVITED("invitation"),
    INVITATION_REVOKED("invitation"),
    INVITATION_ACCEPTED("invitation"),
    INTEGRATION_CONNECTED("integration"),
    INTEGRATION_DISCONNECTED("integration"),
    WEBHOOK_GENERATED("webhook"),
    WEBHOOK_ENABLED("webhook"),
    WEBHOOK_DISABLED("webhook"),
    WEBHOOK_DELETED("webhook"),
    /** An inbound webhook request failed authentication. Anonymous: no actor. */
    WEBHOOK_AUTH_FAILED("webhook"),
    PASSWORD_CHANGED("user"),
    ORGANIZATION_RENAMED("organization"),
    /** A non-terminal execution was re-queued on backend startup. Anonymous: no actor. */
    EXECUTION_RECOVERED("execution"),
    INTEGRATION_CREDENTIAL_UPDATED("integration"),
    INCIDENT_ACKNOWLEDGED("incident"),
    INCIDENT_RESOLVED("incident");

    private final String targetType;

    AuditAction(String targetType) {
        this.targetType = targetType;
    }

    public String targetType() {
        return targetType;
    }
}
