package com.flowops.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.domain.AlertDelivery;
import com.flowops.repository.AlertDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for reliable outbound alert delivery, deduplication, and observability.
 * Prevents alert storms via deduplication windows and records delivery failures.
 */
@Service
public class AlertDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AlertDeliveryService.class);
    private static final Duration DEFAULT_DEDUP_WINDOW = Duration.ofMinutes(15);

    private final AlertDeliveryRepository repository;

    public AlertDeliveryService(AlertDeliveryRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks if an alert with this dedupKey was already sent within the deduplication window.
     * Returns true if it is safe to deliver, false if it should be suppressed to avoid alert storms.
     */
    @Transactional(readOnly = true)
    public boolean shouldDeliver(UUID organizationId, String dedupKey, Duration dedupWindow) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return true;
        }
        Duration window = dedupWindow != null ? dedupWindow : DEFAULT_DEDUP_WINDOW;
        Instant since = Instant.now().minus(window);
        List<AlertDelivery> recent = repository.findRecentByDedupKey(organizationId, dedupKey, since);
        if (!recent.isEmpty()) {
            log.info("Alert suppressed by deduplication (org={}, dedupKey={}, count={})",
                    organizationId, dedupKey, recent.size());
            return false;
        }
        return true;
    }

    @Transactional
    public AlertDelivery createPending(
            UUID organizationId,
            UUID incidentId,
            String channel,
            String destination,
            String eventType,
            String dedupKey,
            JsonNode payload) {

        AlertDelivery delivery = AlertDelivery.create(
                organizationId,
                incidentId,
                channel,
                destination,
                eventType,
                dedupKey,
                payload);

        return repository.save(delivery);
    }

    @Transactional
    public void markDelivered(UUID deliveryId) {
        repository.findById(deliveryId).ifPresent(d -> {
            d.markDelivered();
            repository.save(d);
            log.info("Alert delivered successfully (id={}, channel={}, dest={})",
                    deliveryId, d.getChannel(), d.getDestination());
        });
    }

    @Transactional
    public void markFailed(UUID deliveryId, String error) {
        repository.findById(deliveryId).ifPresent(d -> {
            d.markFailed(error);
            repository.save(d);
            log.warn("Alert delivery failed (id={}, channel={}, dest={}): {}",
                    deliveryId, d.getChannel(), d.getDestination(), error);
        });
    }

    @Transactional(readOnly = true)
    public List<AlertDelivery> listDeliveries(
            UUID organizationId,
            String channel,
            String status,
            int page,
            int size) {
        return repository.search(organizationId, channel, status, PageRequest.of(page, Math.min(size, 100)));
    }
}
