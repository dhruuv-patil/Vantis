package com.flowops.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.TriggerType;
import com.flowops.domain.WorkflowExecution;
import com.flowops.repository.WorkflowExecutionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists quota-rejected executions with a distinct rejection_reason and error.
 * Excluded from failure metrics, alert listeners, and anomaly baselines.
 * Deduplicates / rate-limits repeated rejections to max 1 row per org per minute with a counter
 * to prevent table bloat.
 * Writes in its own transaction (REQUIRES_NEW) so the row survives the thrown exception.
 * Does not swallow exceptions inside REQUIRES_NEW.
 * Evicts expired entries from the fallback memory map.
 * Stores and counts suppressed rejections on the recent rejected execution row and via metrics.
 */
@Service
public class RejectedExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(RejectedExecutionRecorder.class);
    private static final Duration RATE_LIMIT_TTL = Duration.ofMinutes(2);

    private final WorkflowExecutionRepository executionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> inMemoryCounters = new ConcurrentHashMap<>();

    @Autowired
    public RejectedExecutionRecorder(
            WorkflowExecutionRepository executionRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper mapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.executionRepository = executionRepository;
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    public RejectedExecutionRecorder(
            WorkflowExecutionRepository executionRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper mapper) {
        this(executionRepository, redisTemplate, mapper, null);
    }

    /**
     * Records a rejected execution row if the per-org per-minute rate limit allows.
     * When repeated rejections occur within the same window, stores and counts suppressed
     * rejections by incrementing rejection_count on the latest rejected row and metric counter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(
            UUID organizationId,
            UUID workflowId,
            UUID workflowVersionId,
            int versionNumber,
            TriggerType triggerType,
            UUID createdBy,
            String rejectionReason,
            String errorMessage) {

        long minuteWindow = Instant.now().getEpochSecond() / 60;
        evictExpiredFallbackCounters(minuteWindow);
        long counter = incrementCounter(organizationId, minuteWindow);

        if (counter > 1) {
            log.debug("Rate-limiting quota rejection row creation for org={} minuteWindow={} count={}",
                    organizationId, minuteWindow, counter);
            // Store suppressed rejection count on the recent rejected execution row
            Optional<WorkflowExecution> latestOpt = executionRepository
                    .findTopByOrganizationIdAndRejectionReasonIsNotNullOrderByCreatedAtDesc(organizationId);
            if (latestOpt.isPresent()) {
                WorkflowExecution latest = latestOpt.get();
                if (latest.getCreatedAt().isAfter(Instant.now().minusSeconds(120))) {
                    latest.setRejectionCount(latest.getRejectionCount() + 1);
                    executionRepository.save(latest);
                }
            }
            // Count suppressed rejection via metric counter
            if (meterRegistry != null) {
                try {
                    meterRegistry.counter("executions.rejected.suppressed", "organization", organizationId.toString()).increment();
                } catch (Exception ignored) {
                }
            }
            return;
        }

        // New rejection row for this minute window
        WorkflowExecution rejected = WorkflowExecution.createRejected(
                organizationId,
                workflowId,
                workflowVersionId,
                versionNumber,
                triggerType,
                mapper.createObjectNode(),
                createdBy,
                rejectionReason,
                errorMessage);

        executionRepository.save(rejected);
        log.info("Recorded rejected execution id={} org={} workflow={} reason={}",
                rejected.getId(), organizationId, workflowId, rejectionReason);
    }

    private void evictExpiredFallbackCounters(long currentMinuteWindow) {
        inMemoryCounters.keySet().removeIf(k -> {
            try {
                String[] parts = k.split(":");
                long window = Long.parseLong(parts[parts.length - 1]);
                return window < currentMinuteWindow - 1;
            } catch (Exception e) {
                return true;
            }
        });
    }

    private long incrementCounter(UUID organizationId, long minuteWindow) {
        String key = "rate:rejected_run:" + organizationId + ":" + minuteWindow;
        if (redisTemplate != null) {
            try {
                Long val = redisTemplate.opsForValue().increment(key);
                if (val != null && val == 1L) {
                    redisTemplate.expire(key, RATE_LIMIT_TTL);
                }
                return val != null ? val : 1L;
            } catch (Exception e) {
                log.warn("Redis counter increment failed for rejected runs (falling back to memory): {}", e.getMessage());
            }
        }

        // In-memory fallback
        return inMemoryCounters.compute(key, (k, existing) -> {
            if (existing == null) {
                return new AtomicInteger(1);
            }
            existing.incrementAndGet();
            return existing;
        }).get();
    }
}
