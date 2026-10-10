package com.flowops.billing.provider;

import com.flowops.domain.BillingEvent;
import com.flowops.repository.BillingEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic database-level event claimer.
 * Uses PostgreSQL atomic INSERT ... ON CONFLICT (provider, provider_event_id) DO NOTHING.
 * Guaranteed to be atomic across multiple threads, JVMs, and replica pods without any
 * JVM-local locking, String.intern(), or SELECT-before-INSERT race conditions.
 * Duplicate deliveries cleanly return Optional.empty() without marking any transaction rollback-only.
 */
@Component
public class BillingEventClaimer {

    private static final Logger log = LoggerFactory.getLogger(BillingEventClaimer.class);

    private static final String POSTGRES_INSERT_ON_CONFLICT_SQL = """
            INSERT INTO billing_events (
                id,
                provider,
                provider_event_id,
                organization_id,
                event_type,
                event_timestamp,
                payload,
                status,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            """;

    private static final String H2_INSERT_WHERE_NOT_EXISTS_SQL = """
            INSERT INTO billing_events (
                id,
                provider,
                provider_event_id,
                organization_id,
                event_type,
                event_timestamp,
                payload,
                status,
                created_at,
                updated_at
            ) SELECT ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM billing_events WHERE provider = ? AND provider_event_id = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BillingEventRepository billingEventRepository;
    private final boolean isPostgres;

    public BillingEventClaimer(
            JdbcTemplate jdbcTemplate,
            BillingEventRepository billingEventRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.billingEventRepository = billingEventRepository;
        this.isPostgres = checkIsPostgres(jdbcTemplate);
    }

    private static boolean checkIsPostgres(JdbcTemplate jdbcTemplate) {
        try {
            Boolean result = jdbcTemplate.execute((java.sql.Connection conn) -> {
                String dbName = conn.getMetaData().getDatabaseProductName();
                return dbName != null && dbName.toLowerCase().contains("postgres");
            });
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Could not determine database product name, assuming PostgreSQL", e);
            return true;
        }
    }

    public Optional<BillingEvent> tryClaim(
            String provider,
            String eventId,
            String eventType,
            Instant eventTimestamp,
            String rawPayload,
            UUID organizationId) {

        UUID id = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Timestamp nowTs = Timestamp.from(now);
        Timestamp eventTs = eventTimestamp != null ? Timestamp.from(eventTimestamp) : nowTs;

        try {
            int rowsAffected;
            if (isPostgres) {
                rowsAffected = jdbcTemplate.update(
                        POSTGRES_INSERT_ON_CONFLICT_SQL,
                        id,
                        provider,
                        eventId,
                        organizationId,
                        eventType,
                        eventTs,
                        rawPayload,
                        nowTs,
                        nowTs
                );
            } else {
                rowsAffected = jdbcTemplate.update(
                        H2_INSERT_WHERE_NOT_EXISTS_SQL,
                        id,
                        provider,
                        eventId,
                        organizationId,
                        eventType,
                        eventTs,
                        rawPayload,
                        nowTs,
                        nowTs,
                        provider,
                        eventId
                );
            }

            if (rowsAffected == 0) {
                // Another concurrent caller or replica pod claimed the provider event
                return Optional.empty();
            }

            return billingEventRepository.findById(id);
        } catch (DataIntegrityViolationException dive) {
            // Under concurrent H2 execution, constraint violation indicates already claimed
            log.info("Duplicate event rejected by database unique constraint [provider={}, eventId={}]",
                    provider, eventId);
            return Optional.empty();
        } catch (DataAccessException ex) {
            log.error("Unexpected database error while atomically claiming billing event [provider={}, eventId={}]",
                    provider, eventId, ex);
            throw ex;
        }
    }
}
