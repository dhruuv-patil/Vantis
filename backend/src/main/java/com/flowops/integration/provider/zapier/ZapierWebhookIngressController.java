package com.flowops.integration.provider.zapier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.domain.IdempotencyKey;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.domain.IntegrationWorkflow;
import com.flowops.domain.Role;
import com.flowops.execution.ExecutionEventService;
import com.flowops.integration.IntegrationWorkflowService;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.reliability.detect.ReliabilityDetectionService;
import com.flowops.reliability.telemetry.TelemetryCollector;
import com.flowops.repository.IdempotencyKeyRepository;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.security.FlowOpsPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, authenticated inbound webhook endpoint for Zapier telemetry:
 * {@code POST /api/webhooks/zapier/{integrationId}}.
 *
 * <p>Authentication & Security Contract:
 * <ul>
 *   <li>HMAC-SHA256 signature verification using the integration's stored {@code webhookSecret}.</li>
 *   <li>Replay prevention: requires {@code X-Vantis-Timestamp} within ±300s of server time.</li>
 *   <li>Deduplication: cached execution nonce / ID prevents duplicate ingestion within 10m window.</li>
 *   <li>Strict tenant isolation: organization ID is read strictly from the integration entity,
 *       never from the incoming client payload.</li>
 *   <li>Rate-limited per integration (120 req/min).</li>
 * </ul>
 */
@RestController
public class ZapierWebhookIngressController {

    private static final Logger log = LoggerFactory.getLogger(ZapierWebhookIngressController.class);

    private static final int RATE_LIMIT = 120;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofSeconds(300);
    private static final Duration REPLAY_CACHE_TTL = Duration.ofMinutes(10);

    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository credentialsRepo;
    private final CredentialCipher cipher;
    private final IntegrationWorkflowService workflowService;
    private final ExecutionEventService eventService;
    private final TelemetryCollector telemetryCollector;
    private final ReliabilityDetectionService detectionService;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;

    public ZapierWebhookIngressController(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentialsRepo,
            CredentialCipher cipher,
            IntegrationWorkflowService workflowService,
            ExecutionEventService eventService,
            TelemetryCollector telemetryCollector,
            ReliabilityDetectionService detectionService,
            IdempotencyKeyRepository idempotencyKeys,
            RateLimiter rateLimiter,
            ObjectMapper mapper) {
        this.integrations = integrations;
        this.credentialsRepo = credentialsRepo;
        this.cipher = cipher;
        this.workflowService = workflowService;
        this.eventService = eventService;
        this.telemetryCollector = telemetryCollector;
        this.detectionService = detectionService;
        this.idempotencyKeys = idempotencyKeys;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
    }

    @PostMapping("/api/webhooks/zapier/{integrationId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable("integrationId") String integrationIdRaw,
            @RequestHeader(value = "X-Vantis-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "X-Signature", required = false) String fallbackSignatureHeader,
            @RequestHeader(value = "X-Vantis-Timestamp", required = false) String timestampHeader,
            @RequestHeader(value = "X-Timestamp", required = false) String fallbackTimestampHeader,
            @RequestHeader(value = "X-Vantis-Nonce", required = false) String nonceHeader,
            HttpServletRequest request) {

        UUID integrationId = parseUuid(integrationIdRaw);

        // 1. Rate limiting before touching database or payload
        rateLimiter.check("webhook:zapier:" + integrationId, RATE_LIMIT, RATE_WINDOW);

        // 2. Read capped request body
        byte[] body = readCappedBody(request);

        // 3. Resolve integration (tenant scoping)
        Integration integration = integrations.findById(integrationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        if (integration.isDisabled() || !"zapier".equalsIgnoreCase(integration.getType())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        // 4. Resolve credentials and webhook secret
        IntegrationCredential credEntity = credentialsRepo.findByIntegrationId(integrationId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Webhook credentials missing"));

        Map<String, String> credMap = cipher.decryptToMap(credEntity.getCiphertext());
        String secret = credMap.get("webhookSecret");
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Webhook secret not configured on integration");
        }

        // 5. Verify timestamp tolerance (Replay prevention)
        String activeTimestampHeader = timestampHeader != null ? timestampHeader : fallbackTimestampHeader;
        if (activeTimestampHeader == null || activeTimestampHeader.isBlank()) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Missing X-Vantis-Timestamp header");
        }

        Instant requestTime = parseTimestampHeader(activeTimestampHeader);
        if (requestTime == null || !isTimestampWithinTolerance(requestTime, TIMESTAMP_TOLERANCE)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Timestamp outside valid ±300s replay window");
        }

        // 6. Verify HMAC signature
        String activeSignature = signatureHeader != null ? signatureHeader : fallbackSignatureHeader;
        if (activeSignature == null || activeSignature.isBlank()) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Missing X-Vantis-Signature header");
        }

        if (!verifyHmac(secret, activeTimestampHeader, body, activeSignature)) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Invalid HMAC signature");
        }

        // 7. Parse body & extract payload fields
        JsonNode payload = parseJson(body);
        String workflowExternalId = extractText(payload, "zapId", "workflowId", "zap_id", "workflow_id");
        if (workflowExternalId == null || workflowExternalId.isBlank()) {
            workflowExternalId = "zap_default";
        }

        String workflowName = extractText(payload, "zapName", "workflowName", "zap_name", "name");
        if (workflowName == null || workflowName.isBlank()) {
            workflowName = "Zapier Workflow";
        }

        String executionExternalId = extractText(payload, "executionId", "runId", "execution_id", "id");
        if (executionExternalId == null || executionExternalId.isBlank()) {
            executionExternalId = UUID.randomUUID().toString();
        }

        // 8. Replay prevention & Deduplication key check
        String dedupeKey = nonceHeader != null && !nonceHeader.isBlank()
                ? nonceHeader
                : executionExternalId;
        String replayCacheKey = "zapier:" + integrationId + ":" + dedupeKey;

        Optional<IdempotencyKey> existingKey = idempotencyKeys.findByKey(replayCacheKey);
        if (existingKey.isPresent()) {
            log.info("Duplicate Zapier webhook event detected for integration {} (dedupeKey: {})",
                    integrationId, dedupeKey);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "ACCEPTED",
                    "duplicate", true,
                    "executionId", existingKey.get().getExecutionId().toString()
            ));
        }

        // 9. Map status & timestamps
        String statusRaw = extractText(payload, "status", "state");
        String status = mapStatus(statusRaw);

        Instant startedAt = parseInstant(firstNonNull(
                payload.get("startedAt"), payload.get("start_time"), payload.get("timestamp")));
        if (startedAt == null) {
            startedAt = requestTime;
        }

        Instant finishedAt = parseInstant(firstNonNull(
                payload.get("finishedAt"), payload.get("end_time"), payload.get("finished")));
        if (finishedAt == null) {
            finishedAt = Instant.now();
        }

        long durationMs = payload.hasNonNull("durationMs")
                ? payload.get("durationMs").asLong()
                : (payload.hasNonNull("duration")
                        ? payload.get("duration").asLong()
                        : Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()));

        Integer itemCount = payload.hasNonNull("itemCount")
                ? payload.get("itemCount").asInt()
                : (payload.hasNonNull("item_count") ? payload.get("item_count").asInt() : null);

        Map<String, Object> outputSignature = null;
        if (payload.hasNonNull("outputSignature") && payload.get("outputSignature").isObject()) {
            outputSignature = mapper.convertValue(payload.get("outputSignature"), Map.class);
        } else if (payload.hasNonNull("output_signature") && payload.get("output_signature").isTextual()) {
            outputSignature = Map.of("signature", payload.get("output_signature").asText());
        }

        String errorMessage = extractText(payload, "errorMessage", "error_message", "error");
        String errorCode = extractText(payload, "errorCode", "error_code");

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (payload.hasNonNull("metadata") && payload.get("metadata").isObject()) {
            metadata.putAll(mapper.convertValue(payload.get("metadata"), Map.class));
        }
        metadata.put("source", "ZAPIER_WEBHOOK");
        metadata.put("rawPayload", payload);

        // 10. Tenant-scoped synthetic principal & workflow mapping
        UUID organizationId = integration.getOrganizationId();
        FlowOpsPrincipal principal = new FlowOpsPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                organizationId,
                Role.ADMIN,
                "zapier-webhook@vantis.internal"
        );

        IntegrationWorkflow extWf = workflowService.getOrCreate(
                integration,
                workflowExternalId,
                Map.of("name", workflowName, "source", "ZAPIER_WEBHOOK")
        );

        UUID runUuid = UUID.nameUUIDFromBytes(
                (integrationId.toString() + ":" + executionExternalId).getBytes(StandardCharsets.UTF_8));

        // 11. Ingest into ExecutionEventService
        ExecutionEventEnvelopes.IngestExecutionEventRequest eventRequest =
                new ExecutionEventEnvelopes.IngestExecutionEventRequest(
                        "ZAPIER",
                        workflowExternalId,
                        executionExternalId,
                        "zap_run",
                        workflowName,
                        status,
                        startedAt,
                        startedAt,
                        finishedAt,
                        durationMs,
                        0,
                        null,
                        null,
                        itemCount,
                        outputSignature,
                        null,
                        itemCount != null ? "COMPLETE" : "WORKFLOW_ONLY",
                        0,
                        errorCode != null ? errorCode : ("FAILED".equals(status) ? "execution_failed" : null),
                        errorMessage,
                        metadata,
                        workflowName,
                        integration.getId(),
                        extWf.getId()
                );

        eventService.ingestEventInternal(principal, eventRequest);

        // 12. Save Idempotency / Replay cache
        idempotencyKeys.save(IdempotencyKey.create(replayCacheKey, runUuid, REPLAY_CACHE_TTL));

        // 13. Telemetry & Anomaly Detection Trigger
        try {
            telemetryCollector.captureExternal(extWf.getId(), null);
        } catch (Exception e) {
            log.warn("Telemetry capture failed for Zapier workflow {}: {}", extWf.getId(), e.getMessage());
        }

        try {
            detectionService.detectInExecution(runUuid);
        } catch (Exception e) {
            log.warn("Anomaly detection failed for Zapier execution {}: {}", runUuid, e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "ACCEPTED",
                "duplicate", false,
                "executionId", runUuid.toString(),
                "externalExecutionId", executionExternalId
        ));
    }

    public static boolean verifyHmac(
            String expectedSecret,
            String timestampHeader,
            byte[] bodyBytes,
            String receivedSignature) {
        if (expectedSecret == null || expectedSecret.isBlank()
                || receivedSignature == null || receivedSignature.isBlank()) {
            return false;
        }

        String cleanSig = receivedSignature.trim();
        if (cleanSig.startsWith("sha256=")) {
            cleanSig = cleanSig.substring(7);
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    expectedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);

            // Mode 1: Timestamp + "." + body
            if (timestampHeader != null && !timestampHeader.isBlank()) {
                byte[] timestampPrefix = (timestampHeader + ".").getBytes(StandardCharsets.UTF_8);
                mac.update(timestampPrefix);
                mac.update(bodyBytes);
                byte[] computedHash = mac.doFinal();
                String computedHex = HexFormat.of().formatHex(computedHash);
                if (MessageDigest.isEqual(
                        cleanSig.toLowerCase().getBytes(StandardCharsets.UTF_8),
                        computedHex.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }

            // Mode 2: Raw body only
            mac.reset();
            mac.init(secretKey);
            byte[] rawHash = mac.doFinal(bodyBytes);
            String rawHex = HexFormat.of().formatHex(rawHash);
            return MessageDigest.isEqual(
                    cleanSig.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    rawHex.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    static Instant parseTimestampHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long val = Long.parseLong(header.trim());
            if (val > 10_000_000_000L) {
                return Instant.ofEpochMilli(val);
            } else if (val > 0) {
                return Instant.ofEpochSecond(val);
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(header.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isTimestampWithinTolerance(Instant timestamp, Duration tolerance) {
        if (timestamp == null) {
            return false;
        }
        Instant now = Instant.now();
        Duration diff = Duration.between(timestamp, now).abs();
        return diff.compareTo(tolerance) <= 0;
    }

    private static String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SUCCEEDED";
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "success", "succeeded", "ok" -> "SUCCEEDED";
            case "3", "error", "failed", "failure" -> "FAILED";
            case "running", "in_progress", "pending" -> "RUNNING";
            case "canceled", "cancelled", "aborted" -> "CANCELED";
            default -> "SUCCEEDED";
        };
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    private byte[] readCappedBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
            }
            return bytes;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST);
        }
    }

    private JsonNode parseJson(byte[] body) {
        if (body == null || body.length == 0) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node == null || node.isMissingNode() ? mapper.createObjectNode() : node;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST);
        }
    }

    private static String extractText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode val = node.get(key);
            if (val != null && !val.isNull()) {
                String text = val.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static JsonNode firstNonNull(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && !n.isNull() && !n.isMissingNode()) {
                return n;
            }
        }
        return null;
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            long val = node.asLong();
            return val > 10_000_000_000L ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            try {
                long epoch = Long.parseLong(text);
                return epoch > 10_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }
}
