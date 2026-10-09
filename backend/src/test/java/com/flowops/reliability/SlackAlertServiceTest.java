package com.flowops.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.security.http.HardenedHttpClient;
import com.flowops.common.security.http.HardenedHttpRequest;
import com.flowops.common.security.http.HardenedHttpResponse;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyType;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.execution.ExecutionStore;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SlackAlertServiceTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T00/B00/X00";
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private IntegrationRepository integrationRepo;
    private IntegrationCredentialRepository credentialRepo;
    private CredentialCipher cipher;
    private ExecutionStore executionStore;
    private ObjectMapper mapper;
    private HardenedHttpClient httpClient;
    private SlackAlertService slackAlertService;

    @BeforeEach
    void setUp() {
        integrationRepo = mock(IntegrationRepository.class);
        credentialRepo = mock(IntegrationCredentialRepository.class);
        cipher = mock(CredentialCipher.class);
        executionStore = mock(ExecutionStore.class);
        mapper = new ObjectMapper();
        httpClient = mock(HardenedHttpClient.class);

        slackAlertService = new SlackAlertService(
                integrationRepo,
                credentialRepo,
                cipher,
                executionStore,
                mapper,
                httpClient
        );
    }

    private HardenedHttpResponse createResponse(int statusCode) {
        return new HardenedHttpResponse(statusCode, Map.of(), "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("1. Sensitive Data Redaction")
    class RedactionTests {

        @Test
        @DisplayName("Redacts Bearer tokens, API keys, passwords, secrets, and auth headers")
        void redactsSensitiveTokens() {
            String raw = "Alert: Bearer eyJhbGciOiJIUzI1NiJ9.test and api_key: secret_key_12345 "
                    + "and password=\"topsecret\" with secret='my_token' and Authorization: Basic dXNlcjpwYXNz";

            String redacted = SlackAlertService.redactSensitiveData(raw);

            assertThat(redacted)
                    .doesNotContain("eyJhbGciOiJIUzI1NiJ9.test")
                    .doesNotContain("secret_key_12345")
                    .doesNotContain("topsecret")
                    .doesNotContain("my_token")
                    .doesNotContain("Basic dXNlcjpwYXNz")
                    .contains("Bearer [REDACTED]")
                    .contains("api_key: [REDACTED]")
                    .contains("password=\"[REDACTED]\"")
                    .contains("secret='[REDACTED]'")
                    .contains("Authorization: [REDACTED]");
        }

        @Test
        @DisplayName("Handles null and clean strings without mutation")
        void handlesNullAndCleanStrings() {
            assertThat(SlackAlertService.redactSensitiveData(null)).isNull();
            assertThat(SlackAlertService.redactSensitiveData("Workflow node latency spike"))
                    .isEqualTo("Workflow node latency spike");
        }
    }

    @Nested
    @DisplayName("2. Synchronous Delivery and HTTP Interaction")
    class DeliveryTests {

        @Test
        @DisplayName("Delivers alert on HTTP 200 with attempt count 1")
        void deliversSuccessfullyOnHttp200() {
            when(httpClient.execute(any(HardenedHttpRequest.class))).thenReturn(createResponse(200));

            SlackAlertService.DeliveryReport report = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "Test Anomaly Detected", UUID.randomUUID(), WORKFLOW_ID);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.DELIVERED);
            assertThat(report.attempts()).isEqualTo(1);
            assertThat(report.httpStatus()).isEqualTo(200);

            ArgumentCaptor<HardenedHttpRequest> captor = ArgumentCaptor.forClass(HardenedHttpRequest.class);
            verify(httpClient).execute(captor.capture());
            assertThat(captor.getValue().url()).isEqualTo(WEBHOOK_URL);
            String body = new String(captor.getValue().body(), StandardCharsets.UTF_8);
            assertThat(body).contains("Test Anomaly Detected");
        }

        @Test
        @DisplayName("Retries on HTTP 429 rate-limit and delivers on subsequent attempt")
        void retriesOnRateLimitAndDelivers() {
            when(httpClient.execute(any(HardenedHttpRequest.class)))
                    .thenReturn(createResponse(429))
                    .thenReturn(createResponse(200));

            SlackAlertService.DeliveryReport report = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "Rate Limit Retry Test", UUID.randomUUID(), WORKFLOW_ID);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.DELIVERED);
            assertThat(report.attempts()).isEqualTo(2);
            assertThat(report.httpStatus()).isEqualTo(200);
            verify(httpClient, times(2)).execute(any());
        }

        @Test
        @DisplayName("Retries on HTTP 500 server error and delivers on subsequent attempt")
        void retriesOn500AndDelivers() {
            when(httpClient.execute(any(HardenedHttpRequest.class)))
                    .thenReturn(createResponse(500))
                    .thenReturn(createResponse(200));

            SlackAlertService.DeliveryReport report = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "500 Retry Test", UUID.randomUUID(), WORKFLOW_ID);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.DELIVERED);
            assertThat(report.attempts()).isEqualTo(2);
            assertThat(report.httpStatus()).isEqualTo(200);
            verify(httpClient, times(2)).execute(any());
        }

        @Test
        @DisplayName("Enters DEAD_LETTER status when retries are exhausted after 3 attempts")
        void entersDeadLetterAfterRetriesExhausted() {
            when(httpClient.execute(any(HardenedHttpRequest.class)))
                    .thenReturn(createResponse(503))
                    .thenReturn(createResponse(503))
                    .thenReturn(createResponse(503));

            SlackAlertService.DeliveryReport report = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "Dead Letter Test", UUID.randomUUID(), WORKFLOW_ID);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.DEAD_LETTER);
            assertThat(report.attempts()).isEqualTo(3);
            assertThat(report.httpStatus()).isEqualTo(503);
            verify(httpClient, times(3)).execute(any());
        }

        @Test
        @DisplayName("Fails immediately without retrying on client errors (HTTP 400 Bad Request)")
        void failsImmediatelyOnClientErrors() {
            when(httpClient.execute(any(HardenedHttpRequest.class)))
                    .thenReturn(createResponse(400));

            SlackAlertService.DeliveryReport report = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "Client Error Test", UUID.randomUUID(), WORKFLOW_ID);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.FAILED);
            assertThat(report.attempts()).isEqualTo(1);
            assertThat(report.httpStatus()).isEqualTo(400);
            verify(httpClient, times(1)).execute(any());
        }
    }

    @Nested
    @DisplayName("3. Alert Flood Rate Limiting")
    class AlertFloodTests {

        @Test
        @DisplayName("Allows up to 5 alerts per minute, throttles 6th alert with RATE_LIMITED and sends digest warning")
        void throttlesAlertFlood() {
            when(httpClient.execute(any(HardenedHttpRequest.class))).thenReturn(createResponse(200));

            UUID floodWorkflowId = UUID.randomUUID();

            List<SlackAlertService.DeliveryReport> reports = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                reports.add(slackAlertService.sendToSlackSynchronous(
                        WEBHOOK_URL, "Alert #" + (i + 1), UUID.randomUUID(), floodWorkflowId));
            }

            // All first 5 alerts are DELIVERED
            for (SlackAlertService.DeliveryReport r : reports) {
                assertThat(r.status()).isEqualTo(SlackAlertService.DeliveryStatus.DELIVERED);
            }

            // 6th alert is throttled
            SlackAlertService.DeliveryReport floodReport = slackAlertService.sendToSlackSynchronous(
                    WEBHOOK_URL, "Alert #6", UUID.randomUUID(), floodWorkflowId);

            assertThat(floodReport.status()).isEqualTo(SlackAlertService.DeliveryStatus.RATE_LIMITED);

            // Verify that 5 normal alerts + 1 digest flood warning were executed = 6 HTTP calls
            verify(httpClient, times(6)).execute(any(HardenedHttpRequest.class));
        }
    }

    @Nested
    @DisplayName("4. End-to-End Anomaly Alert Integration")
    class EndToEndAnomalyAlertTests {

        @Test
        @DisplayName("Builds and delivers anomaly alert when Slack integration is connected")
        void deliversAnomalyAlertForConnectedIntegration() {
            Integration integration = Integration.createConnected(
                    ORG_ID, UUID.randomUUID(), Integration.TYPE_SLACK, "Slack Team", "xoxb-1234");

            IntegrationCredential cred = IntegrationCredential.create(integration.getId(), "encrypted-webhook");

            when(integrationRepo.findByOrganizationIdAndTypeAndStatus(ORG_ID, Integration.TYPE_SLACK, Integration.STATUS_CONNECTED))
                    .thenReturn(Optional.of(integration));
            when(credentialRepo.findByIntegrationId(integration.getId()))
                    .thenReturn(Optional.of(cred));
            when(cipher.decrypt("encrypted-webhook"))
                    .thenReturn(WEBHOOK_URL);
            when(httpClient.execute(any(HardenedHttpRequest.class)))
                    .thenReturn(createResponse(200));

            Anomaly anomaly = Anomaly.open(
                    ORG_ID, WORKFLOW_ID, "http-request-1", UUID.randomUUID(),
                    AnomalyType.OUTPUT, AnomalySeverity.HIGH,
                    "OUTPUT_SIZE", "50 items", "0 items",
                    5.0, 0.95, Map.of("kind", "zero-output"), "OUTPUT:http-request-1:zero_output"
            );

            SlackAlertService.DeliveryReport report = slackAlertService.sendAnomalyAlertSynchronous(ORG_ID, anomaly);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.DELIVERED);
            assertThat(report.attempts()).isEqualTo(1);

            ArgumentCaptor<HardenedHttpRequest> captor = ArgumentCaptor.forClass(HardenedHttpRequest.class);
            verify(httpClient).execute(captor.capture());
            String requestBody = new String(captor.getValue().body(), StandardCharsets.UTF_8);
            assertThat(requestBody).contains("http-request-1").contains("OUTPUT_SIZE").contains("HIGH");
        }

        @Test
        @DisplayName("Returns FAILED delivery report when Slack integration is missing")
        void failsGracefullyWhenSlackNotConnected() {
            when(integrationRepo.findByOrganizationIdAndTypeAndStatus(ORG_ID, Integration.TYPE_SLACK, Integration.STATUS_CONNECTED))
                    .thenReturn(Optional.empty());

            Anomaly anomaly = Anomaly.open(
                    ORG_ID, WORKFLOW_ID, "node-1", UUID.randomUUID(),
                    AnomalyType.LATENCY, AnomalySeverity.MEDIUM,
                    "LATENCY", "100ms", "500ms",
                    3.5, 0.85, Map.of(), "LATENCY:node-1"
            );

            SlackAlertService.DeliveryReport report = slackAlertService.sendAnomalyAlertSynchronous(ORG_ID, anomaly);

            assertThat(report.status()).isEqualTo(SlackAlertService.DeliveryStatus.FAILED);
            assertThat(report.errorMessage()).contains("No connected Slack integration");
        }
    }
}
