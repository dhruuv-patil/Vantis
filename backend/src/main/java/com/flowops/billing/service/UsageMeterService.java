package com.flowops.billing.service;

import com.flowops.billing.model.ReservationResult;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic single-statement usage meter service.
 * Performs conditional reservations and refunds inside the caller's request transaction
 * (Propagation.REQUIRED) so that transaction rollback automatically undoes the counter increment,
 * eliminating the need for complex afterCompletion refund synchronization and holding at most
 * 1 database connection per request.
 */
@Service
public class UsageMeterService {

    private static final Logger log = LoggerFactory.getLogger(UsageMeterService.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean isPostgres;

    public UsageMeterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    /**
     * Atomically reserves 1 execution if current usage < limit.
     * Uses single INSERT ... ON CONFLICT DO UPDATE ... WHERE executions < limit RETURNING executions.
     * In LOG mode (limit == Long.MAX_VALUE), omits the WHERE clause to count past the cap.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult reserveExecution(UUID orgId, Instant periodStart, Instant periodEnd, long limit) {
        return reserveMeter(orgId, periodStart, periodEnd, "executions", limit);
    }

    /**
     * Atomically reserves 1 AI generation if current usage < limit.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult reserveAiGeneration(UUID orgId, Instant periodStart, Instant periodEnd, long limit) {
        return reserveMeter(orgId, periodStart, periodEnd, "ai_generations", limit);
    }

    /**
     * Atomically reserves 1 AI investigation in the monthly meter if current usage < limit.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult reserveAiInvestigationMonthly(UUID orgId, Instant periodStart, Instant periodEnd, long limit) {
        return reserveMeter(orgId, periodStart, periodEnd, "ai_investigations", limit);
    }

    /**
     * Atomically reserves 1 lifetime trial AI investigation if current trial usage < limit.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult reserveAiInvestigationTrial(UUID orgId, long limit) {
        if (!isPostgres) {
            return reserveAiInvestigationTrialH2(orgId, limit);
        }

        boolean isLogMode = (limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE);
        String sql;
        if (isLogMode) {
            // LOG branch: count past the cap without limit condition
            sql = """
                    INSERT INTO lifetime_usage_counters (organization_id, ai_investigations_trial_used, created_at, updated_at)
                    VALUES (?, 1, now(), now())
                    ON CONFLICT (organization_id)
                    DO UPDATE SET
                        ai_investigations_trial_used = lifetime_usage_counters.ai_investigations_trial_used + 1,
                        updated_at = now()
                    RETURNING ai_investigations_trial_used
                    """;
        } else {
            // ENFORCE branch: conditional update where usage < limit
            sql = """
                    INSERT INTO lifetime_usage_counters (organization_id, ai_investigations_trial_used, created_at, updated_at)
                    VALUES (?, 1, now(), now())
                    ON CONFLICT (organization_id)
                    DO UPDATE SET
                        ai_investigations_trial_used = lifetime_usage_counters.ai_investigations_trial_used + 1,
                        updated_at = now()
                    WHERE lifetime_usage_counters.ai_investigations_trial_used < ?
                    RETURNING ai_investigations_trial_used
                    """;
        }

        try {
            Long newCount;
            if (isLogMode) {
                newCount = jdbcTemplate.query(
                        sql,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        orgId);
            } else {
                newCount = jdbcTemplate.query(
                        sql,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        orgId, limit);
            }

            if (newCount != null) {
                return new ReservationResult(true, true, newCount, limit);
            }

            // Exceeded limit. Fetch current value
            Long current = jdbcTemplate.query(
                    "SELECT ai_investigations_trial_used FROM lifetime_usage_counters WHERE organization_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : 0L,
                    orgId);
            long count = current != null ? current : limit;
            return new ReservationResult(false, false, count, limit);
        } catch (DataAccessException ex) {
            log.error("Error reserving lifetime trial investigation for org={}", orgId, ex);
            throw ex;
        }
    }

    private ReservationResult reserveAiInvestigationTrialH2(UUID orgId, long limit) {
        synchronized (("trial:" + orgId.toString()).intern()) {
            boolean isLogMode = (limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE);
            String updateSql = isLogMode
                    ? "UPDATE lifetime_usage_counters SET ai_investigations_trial_used = ai_investigations_trial_used + 1, updated_at = now() WHERE organization_id = ?"
                    : "UPDATE lifetime_usage_counters SET ai_investigations_trial_used = ai_investigations_trial_used + 1, updated_at = now() WHERE organization_id = ? AND ai_investigations_trial_used < ?";

            int updated = isLogMode
                    ? jdbcTemplate.update(updateSql, orgId)
                    : jdbcTemplate.update(updateSql, orgId, limit);

            if (updated > 0) {
                long count = getLifetimeTrialUsage(orgId);
                return new ReservationResult(true, true, count, limit);
            }
            Long current = jdbcTemplate.query(
                    "SELECT ai_investigations_trial_used FROM lifetime_usage_counters WHERE organization_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    orgId);
            if (current == null) {
                if (limit > 0 || isLogMode) {
                    try {
                        jdbcTemplate.update(
                                "INSERT INTO lifetime_usage_counters (organization_id, ai_investigations_trial_used, created_at, updated_at) VALUES (?, 1, now(), now())",
                                orgId);
                        return new ReservationResult(true, true, 1L, limit);
                    } catch (DataAccessException e) {
                        int retried = isLogMode
                                ? jdbcTemplate.update(updateSql, orgId)
                                : jdbcTemplate.update(updateSql, orgId, limit);
                        if (retried > 0) {
                            long count = getLifetimeTrialUsage(orgId);
                            return new ReservationResult(true, true, count, limit);
                        }
                    }
                } else {
                    return new ReservationResult(false, false, 0L, limit);
                }
            }
            long currentUsage = getLifetimeTrialUsage(orgId);
            return new ReservationResult(false, false, currentUsage, limit);
        }
    }

    /**
     * Refunds 1 execution if previously reserved in this period.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refundExecution(UUID orgId, Instant periodStart) {
        refundMeter(orgId, periodStart, "executions");
    }

    /**
     * Refunds 1 AI generation if previously reserved in this period.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refundAiGeneration(UUID orgId, Instant periodStart) {
        refundMeter(orgId, periodStart, "ai_generations");
    }

    /**
     * Refunds 1 AI investigation in monthly counters.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refundAiInvestigationMonthly(UUID orgId, Instant periodStart) {
        refundMeter(orgId, periodStart, "ai_investigations");
    }

    /**
     * Refunds 1 AI investigation in lifetime trial counters.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refundAiInvestigationTrial(UUID orgId) {
        String sql = """
                UPDATE lifetime_usage_counters
                SET ai_investigations_trial_used = GREATEST(0, ai_investigations_trial_used - 1),
                    updated_at = now()
                WHERE organization_id = ?
                """;
        try {
            jdbcTemplate.update(sql, orgId);
        } catch (DataAccessException ex) {
            log.error("Failed to refund lifetime trial investigation for org={}", orgId, ex);
        }
    }

    /**
     * Returns current usage count for a given meter in the specified period.
     */
    @Transactional(readOnly = true)
    public long getCurrentUsage(UUID orgId, Instant periodStart, String column) {
        Instant normalizedStart = normalizeTimestamp(periodStart);
        String sql = "SELECT " + column + " FROM usage_counters WHERE organization_id = ? AND period_start = ?";
        try {
            Long count = jdbcTemplate.query(
                    sql,
                    rs -> rs.next() ? rs.getLong(1) : 0L,
                    orgId, normalizedStart);
            return count != null ? count : 0L;
        } catch (DataAccessException e) {
            log.warn("Failed to get usage for org={} column={}", orgId, column, e);
            return 0L;
        }
    }

    /**
     * Returns current lifetime trial investigation count.
     */
    @Transactional(readOnly = true)
    public long getLifetimeTrialUsage(UUID orgId) {
        String sql = "SELECT ai_investigations_trial_used FROM lifetime_usage_counters WHERE organization_id = ?";
        try {
            Long count = jdbcTemplate.query(
                    sql,
                    rs -> rs.next() ? rs.getLong(1) : 0L,
                    orgId);
            return count != null ? count : 0L;
        } catch (DataAccessException e) {
            log.warn("Failed to get lifetime trial usage for org={}", orgId, e);
            return 0L;
        }
    }

    private ReservationResult reserveMeter(
            UUID orgId,
            Instant periodStart,
            Instant periodEnd,
            String column,
            long limit) {
        periodStart = normalizeTimestamp(periodStart);
        periodEnd = normalizeTimestamp(periodEnd);
        if (!isPostgres) {
            return reserveMeterH2(orgId, periodStart, periodEnd, column, limit);
        }
        UUID id = UUID.randomUUID();
        boolean isLogMode = (limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE);

        String sql;
        if (isLogMode) {
            // LOG branch: unconditionally count past the cap
            sql = """
                    INSERT INTO usage_counters (id, organization_id, period_start, period_end, %s, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 1, now(), now())
                    ON CONFLICT (organization_id, period_start)
                    DO UPDATE SET
                        %s = usage_counters.%s + 1,
                        updated_at = now()
                    RETURNING %s
                    """.formatted(column, column, column, column);
        } else {
            // ENFORCE branch: conditional increment where usage < limit
            sql = """
                    INSERT INTO usage_counters (id, organization_id, period_start, period_end, %s, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 1, now(), now())
                    ON CONFLICT (organization_id, period_start)
                    DO UPDATE SET
                        %s = usage_counters.%s + 1,
                        updated_at = now()
                    WHERE usage_counters.%s < ?
                    RETURNING %s
                    """.formatted(column, column, column, column, column);
        }

        try {
            Long newCount;
            if (isLogMode) {
                newCount = jdbcTemplate.query(
                        sql,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        id, orgId, periodStart, periodEnd);
            } else {
                newCount = jdbcTemplate.query(
                        sql,
                        rs -> rs.next() ? rs.getLong(1) : null,
                        id, orgId, periodStart, periodEnd, limit);
            }

            if (newCount != null) {
                return new ReservationResult(true, true, newCount, limit);
            }

            // Exceeded limit: counter was not incremented
            long current = getCurrentUsage(orgId, periodStart, column);
            return new ReservationResult(false, false, current, limit);
        } catch (DataAccessException ex) {
            log.error("Failed to reserve meter {} for org={}", column, orgId, ex);
            throw ex;
        }
    }

    private ReservationResult reserveMeterH2(
            UUID orgId,
            Instant periodStart,
            Instant periodEnd,
            String column,
            long limit) {
        synchronized ((orgId.toString() + ":" + periodStart.toString() + ":" + column).intern()) {
            boolean isLogMode = (limit == Long.MAX_VALUE || limit == Integer.MAX_VALUE);
            String updateSql = isLogMode
                    ? "UPDATE usage_counters SET " + column + " = " + column + " + 1, updated_at = now() WHERE organization_id = ? AND period_start = ?"
                    : "UPDATE usage_counters SET " + column + " = " + column + " + 1, updated_at = now() WHERE organization_id = ? AND period_start = ? AND " + column + " < ?";

            int updated = isLogMode
                    ? jdbcTemplate.update(updateSql, orgId, periodStart)
                    : jdbcTemplate.update(updateSql, orgId, periodStart, limit);

            if (updated > 0) {
                long count = getCurrentUsage(orgId, periodStart, column);
                return new ReservationResult(true, true, count, limit);
            }
            Long current = jdbcTemplate.query(
                    "SELECT " + column + " FROM usage_counters WHERE organization_id = ? AND period_start = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    orgId, periodStart);
            if (current == null) {
                if (limit > 0 || isLogMode) {
                    long execVal = "executions".equals(column) ? 1L : 0L;
                    long genVal = "ai_generations".equals(column) ? 1L : 0L;
                    long invVal = "ai_investigations".equals(column) ? 1L : 0L;
                    try {
                        UUID id = UUID.randomUUID();
                        jdbcTemplate.update(
                                "INSERT INTO usage_counters (id, organization_id, period_start, period_end, executions, ai_generations, ai_investigations, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                                id, orgId, periodStart, periodEnd, execVal, genVal, invVal);
                        return new ReservationResult(true, true, 1L, limit);
                    } catch (DataAccessException e) {
                        int retried = isLogMode
                                ? jdbcTemplate.update(updateSql, orgId, periodStart)
                                : jdbcTemplate.update(updateSql, orgId, periodStart, limit);
                        if (retried > 0) {
                            long count = getCurrentUsage(orgId, periodStart, column);
                            return new ReservationResult(true, true, count, limit);
                        }
                    }
                } else {
                    return new ReservationResult(false, false, 0L, limit);
                }
            }
            long currentUsage = getCurrentUsage(orgId, periodStart, column);
            return new ReservationResult(false, false, currentUsage, limit);
        }
    }

    private void refundMeter(UUID orgId, Instant periodStart, String column) {
        if (periodStart == null) {
            return;
        }
        Instant normalizedStart = normalizeTimestamp(periodStart);
        String sql = """
                UPDATE usage_counters
                SET %s = GREATEST(0, %s - 1),
                    updated_at = now()
                WHERE organization_id = ? AND period_start = ?
                """.formatted(column, column);
        try {
            jdbcTemplate.update(sql, orgId, normalizedStart);
        } catch (DataAccessException ex) {
            log.error("Failed to refund meter {} for org={}", column, orgId, ex);
        }
    }

    private static Instant normalizeTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
