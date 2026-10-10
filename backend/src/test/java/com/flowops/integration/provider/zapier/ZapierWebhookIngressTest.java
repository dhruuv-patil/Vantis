package com.flowops.integration.provider.zapier;

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
import com.flowops.execution.ExecutionEventService;
import com.flowops.integration.IntegrationWorkflowService;
import com.flowops.reliability.detect.ReliabilityDetectionService;
import com.flowops.reliability.telemetry.TelemetryCollector;
import com.flowops.repository.IdempotencyKeyRepository;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZapierWebhookIngressTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private IntegrationRepository integrations;
    private IntegrationCredentialRepository credentialsRepo;
    private CredentialCipher cipher;
    private IntegrationWorkflowService workflowService;
    private ExecutionEventService eventService;
    private TelemetryCollector telemetryCollector;
    private ReliabilityDetectionService detectionService;
    private IdempotencyKeyRepository idempotencyKeys;
    private RateLimiter rateLimiter;
    private ZapierWebhookIngressController controller;

    private final UUID integrationId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final String secret = "whsec_test_secret_key_123456";

    @BeforeEach
    void setUp() {
        integrations = mock(IntegrationRepository.class);
        credentialsRepo = mock(IntegrationCredentialRepository.class);
        cipher = mock(CredentialCipher.class);
        workflowService = mock(IntegrationWorkflowService.class);
        eventService = mock(ExecutionEventService.class);
        telemetryCollector = mock(TelemetryCollector.class);
        detectionService = mock(ReliabilityDetectionService.class);
        idempotencyKeys = mock(IdempotencyKeyRepository.class);
        rateLimiter = mock(RateLimiter.class);

        controller = new ZapierWebhookIngressController(
                integrations,
                credentialsRepo,
                cipher,
                workflowService,
                eventService,
                telemetryCollector,
                detectionService,
                idempotencyKeys,
                rateLimiter,
                mapper
        );

        Integration integration = Integration.createConnected(
                organizationId,
                UUID.randomUUID(),
                "zapier",
                "Zapier Production",
                "https://hooks.zapier.com"
        );
        when(integrations.findById(integrationId)).thenReturn(Optional.of(integration));

        IntegrationCredential credential = mock(IntegrationCredential.class);
        when(credential.getCiphertext()).thenReturn("encrypted_token");
        when(credentialsRepo.findByIntegrationId(integrationId)).thenReturn(Optional.of(credential));
        when(cipher.decryptToMap("encrypted_token")).thenReturn(Map.of("webhookSecret", secret));
    }

    @Test
    @DisplayName("Successfully ingests valid webhook telemetry with HMAC signature and fresh timestamp")
    void testValidWebhookTelemetry() throws Exception {
        String body = """
                {
                  "zapId": "zap_1001",
                  "zapName": "Sync Lead to CRM",
                  "executionId": "run_8899",
                  "status": "success",
                  "itemCount": 5,
                  "durationMs": 1250
                }
                """;
        long nowSeconds = Instant.now().getEpochSecond();
        String timestampHeader = String.valueOf(nowSeconds);
        String signature = computeHmac(secret, timestampHeader + "." + body);

        HttpServletRequest request = createMockRequest(body);

        IntegrationWorkflow wf = IntegrationWorkflow.create(
                organizationId, integrationId, "zap_1001", "Sync Lead to CRM");
        when(workflowService.getOrCreate(any(), eq("zap_1001"), any())).thenReturn(wf);
        when(idempotencyKeys.findByKey(any())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.receive(
                integrationId.toString(),
                "sha256=" + signature,
                null,
                timestampHeader,
                null,
                "nonce_1234",
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "ACCEPTED");
        assertThat(response.getBody()).containsEntry("duplicate", false);

        ArgumentCaptor<ExecutionEventEnvelopes.IngestExecutionEventRequest> eventCaptor =
                ArgumentCaptor.forClass(ExecutionEventEnvelopes.IngestExecutionEventRequest.class);
        verify(eventService).ingestEventInternal(any(), eventCaptor.capture());

        ExecutionEventEnvelopes.IngestExecutionEventRequest ingested = eventCaptor.getValue();
        assertThat(ingested.source()).isEqualTo("ZAPIER");
        assertThat(ingested.workflowExternalId()).isEqualTo("zap_1001");
        assertThat(ingested.executionExternalId()).isEqualTo("run_8899");
        assertThat(ingested.status()).isEqualTo("SUCCEEDED");
        assertThat(ingested.itemCount()).isEqualTo(5);
        assertThat(ingested.durationMs()).isEqualTo(1250L);

        verify(idempotencyKeys).save(any(IdempotencyKey.class));
    }

    @Test
    @DisplayName("Rejects webhook with invalid HMAC signature with 401 Unauthorized")
    void testInvalidHmacSignature() {
        String body = "{\"zapId\": \"zap_1001\"}";
        String timestampHeader = String.valueOf(Instant.now().getEpochSecond());
        String badSignature = "sha256=deadbeefcafebabe1234567890abcdefdeadbeefcafebabe1234567890abcdef";

        HttpServletRequest request = createMockRequest(body);

        assertThatThrownBy(() -> controller.receive(
                integrationId.toString(),
                badSignature,
                null,
                timestampHeader,
                null,
                null,
                request
        )).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.code()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
                });

        verify(eventService, never()).ingestEventInternal(any(), any());
    }

    @Test
    @DisplayName("Rejects webhook with stale timestamp outside ±300s replay window")
    void testStaleTimestampRejection() {
        String body = "{\"zapId\": \"zap_1001\"}";
        long staleTimestamp = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();
        String timestampHeader = String.valueOf(staleTimestamp);
        String signature = computeHmac(secret, timestampHeader + "." + body);

        HttpServletRequest request = createMockRequest(body);

        assertThatThrownBy(() -> controller.receive(
                integrationId.toString(),
                "sha256=" + signature,
                null,
                timestampHeader,
                null,
                null,
                request
        )).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                });

        verify(eventService, never()).ingestEventInternal(any(), any());
    }

    @Test
    @DisplayName("Handles duplicate replayed execution by returning 202 with duplicate=true without re-ingesting")
    void testReplayPreventionDeduplication() throws Exception {
        String body = """
                {
                  "zapId": "zap_1001",
                  "executionId": "run_8899",
                  "status": "success"
                }
                """;
        String timestampHeader = String.valueOf(Instant.now().getEpochSecond());
        String signature = computeHmac(secret, timestampHeader + "." + body);

        HttpServletRequest request = createMockRequest(body);

        IdempotencyKey existing = IdempotencyKey.create(
                "zapier:" + integrationId + ":run_8899",
                UUID.randomUUID(),
                Duration.ofMinutes(10)
        );
        when(idempotencyKeys.findByKey(eq("zapier:" + integrationId + ":run_8899")))
                .thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> response = controller.receive(
                integrationId.toString(),
                "sha256=" + signature,
                null,
                timestampHeader,
                null,
                null,
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "ACCEPTED");
        assertThat(response.getBody()).containsEntry("duplicate", true);

        verify(eventService, never()).ingestEventInternal(any(), any());
    }

    @Test
    @DisplayName("Rejects request for unknown or nonexistent integration with opaque 404")
    void testUnknownIntegration() {
        UUID unknownId = UUID.randomUUID();
        when(integrations.findById(unknownId)).thenReturn(Optional.empty());

        HttpServletRequest request = createMockRequest("{}");

        assertThatThrownBy(() -> controller.receive(
                unknownId.toString(),
                "sha256=xxx",
                null,
                String.valueOf(Instant.now().getEpochSecond()),
                null,
                null,
                request
        )).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    private static String computeHmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpServletRequest createMockRequest(String body) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        when(request.getContentLengthLong()).thenReturn((long) bytes.length);
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ServletInputStream sis = new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bis.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return bis.read();
                }
            };
            when(request.getInputStream()).thenReturn(sis);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return request;
    }
}
