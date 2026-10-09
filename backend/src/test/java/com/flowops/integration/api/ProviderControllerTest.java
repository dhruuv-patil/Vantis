package com.flowops.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowops.api.IntegrationResponse;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.CryptoProperties;
import com.flowops.domain.Integration;
import com.flowops.domain.Role;
import com.flowops.integration.IntegrationService;
import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.delivery.NotificationProviderRegistry;
import com.flowops.integration.provider.AiProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.IntegrationProvider;
import com.flowops.integration.provider.IntegrationRegistry;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.ProviderCapabilities;
import com.flowops.integration.provider.WorkflowProvider;
import com.flowops.integration.provider.WorkflowProviderRegistry;
import com.flowops.security.FlowOpsPrincipal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ProviderControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
    private static final FlowOpsPrincipal ADMIN_PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_ID, Role.ADMIN, "admin@flowops.ai");

    private IntegrationService integrationService;
    private CredentialCipher cipher;
    private ProviderController controller;

    private NotificationProvider mockSlack;
    private WorkflowProvider mockGitHub;
    private AiProvider mockOpenAi;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_PRINCIPAL, null, List.of()));

        integrationService = mock(IntegrationService.class);

        byte[] raw = new byte[CryptoProperties.KEY_BYTES];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 2 + 7);
        }
        cipher = new CredentialCipher(new CryptoProperties(Base64.getEncoder().encodeToString(raw)));

        mockSlack = mock(NotificationProvider.class);
        when(mockSlack.type()).thenReturn(IntegrationType.SLACK);
        when(mockSlack.displayName()).thenReturn("Slack");
        when(mockSlack.description()).thenReturn("Slack notifications");
        when(mockSlack.credentialFields()).thenReturn(List.of(
                new CredentialField("webhookUrl", "Webhook URL", true, "https://hooks.slack.com/...", "desc", "url")));

        mockGitHub = mock(WorkflowProvider.class);
        when(mockGitHub.type()).thenReturn(IntegrationType.GITHUB);
        when(mockGitHub.displayName()).thenReturn("GitHub Actions");
        when(mockGitHub.description()).thenReturn("CI/CD workflows");
        when(mockGitHub.capabilities()).thenReturn(new ProviderCapabilities(true, true, false, false, false, false));
        when(mockGitHub.credentialFields()).thenReturn(List.of(
                new CredentialField("token", "Personal Access Token", true, "ghp_...", "token", "password")));

        mockOpenAi = mock(AiProvider.class);
        when(mockOpenAi.type()).thenReturn(IntegrationType.OPENAI);
        when(mockOpenAi.displayName()).thenReturn("OpenAI");
        when(mockOpenAi.description()).thenReturn("OpenAI models");
        when(mockOpenAi.credentialFields()).thenReturn(List.of(
                new CredentialField("apiKey", "API Key", true, "sk-...", "key", "password")));

        List<IntegrationProvider> allProviders = List.of(mockSlack, mockGitHub, mockOpenAi);
        IntegrationRegistry integrationRegistry = new IntegrationRegistry(allProviders);
        WorkflowProviderRegistry workflowRegistry = new WorkflowProviderRegistry(integrationRegistry);
        NotificationProviderRegistry notificationRegistry = new NotificationProviderRegistry(integrationRegistry);

        controller = new ProviderController(
                integrationService,
                workflowRegistry,
                notificationRegistry,
                cipher,
                integrationRegistry);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("listProviders returns all registered providers with valid docsUrl and metadata")
    void listProvidersReturnsAllProvidersWithDocsUrl() {
        var response = controller.listProviders();
        assertThat(response.providers()).hasSize(3);

        var slackInfo = response.providers().stream()
                .filter(p -> p.type().equals("slack"))
                .findFirst().orElseThrow();
        assertThat(slackInfo.name()).isEqualTo("Slack");
        assertThat(slackInfo.docsUrl()).isEqualTo("https://api.slack.com/messaging/webhooks");
        assertThat(slackInfo.credentialFields()).hasSize(1);

        var githubInfo = response.providers().stream()
                .filter(p -> p.type().equals("github"))
                .findFirst().orElseThrow();
        assertThat(githubInfo.name()).isEqualTo("GitHub Actions");
        assertThat(githubInfo.docsUrl()).isEqualTo("https://github.com/settings/tokens");
        assertThat(githubInfo.capabilities().workflowDiscovery()).isTrue();

        var openAiInfo = response.providers().stream()
                .filter(p -> p.type().equals("openai"))
                .findFirst().orElseThrow();
        assertThat(openAiInfo.name()).isEqualTo("OpenAI");
        assertThat(openAiInfo.docsUrl()).isEqualTo("https://platform.openai.com/api-keys");
    }

    @Test
    @DisplayName("connectProvider succeeds for notification provider and validates credentials")
    void connectNotificationProviderSuccess() {
        when(mockSlack.testConnection(any(DeliveryContext.class)))
                .thenReturn(ConnectionTestResult.success("Webhook verified"));

        Integration entity = Integration.createConnected(ORG_ID, USER_ID, "slack", "Team Slack", "8901");

        when(integrationService.connectProvider(
                eq(ADMIN_PRINCIPAL),
                eq(IntegrationType.SLACK),
                eq("Team Slack"),
                any(),
                any(),
                any(),
                any())).thenReturn(entity);

        var req = new ProviderController.ConnectProviderRequest(
                "slack",
                "Team Slack",
                Map.of("webhookUrl", "https://hooks.slack.com/services/T1/B2/token8901"));

        var result = controller.connectProvider(req);
        assertThat(result.type()).isEqualTo("slack");
        assertThat(result.name()).isEqualTo("Team Slack");
        assertThat(result.hint()).isEqualTo("8901");
    }

    @Test
    @DisplayName("connectProvider succeeds for AI provider (OpenAI)")
    void connectAiProviderSuccess() {
        when(mockOpenAi.testConnection(any(IntegrationContext.class)))
                .thenReturn(ConnectionTestResult.success("API key verified"));

        Integration entity = Integration.createConnected(ORG_ID, USER_ID, "openai", "Production OpenAI", "sk42");

        when(integrationService.connectProvider(
                eq(ADMIN_PRINCIPAL),
                eq(IntegrationType.OPENAI),
                eq("Production OpenAI"),
                any(),
                any(),
                any(),
                any())).thenReturn(entity);

        var req = new ProviderController.ConnectProviderRequest(
                "openai",
                "Production OpenAI",
                Map.of("apiKey", "sk-proj-testkey1234sk42"));

        var result = controller.connectProvider(req);
        assertThat(result.type()).isEqualTo("openai");
        assertThat(result.name()).isEqualTo("Production OpenAI");
    }

    @Test
    @DisplayName("connectProvider fails when connection test returns failure")
    void connectProviderFailsOnInvalidConnection() {
        when(mockGitHub.testConnection(any(IntegrationContext.class)))
                .thenReturn(ConnectionTestResult.failure("Invalid Personal Access Token"));

        var req = new ProviderController.ConnectProviderRequest(
                "github",
                "GitHub Actions",
                Map.of("token", "ghp_invalidtoken"));

        assertThatThrownBy(() -> controller.connectProvider(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid Personal Access Token");
    }

    @Test
    @DisplayName("connectProvider rejects unknown provider wire type")
    void connectProviderRejectsUnknownType() {
        var req = new ProviderController.ConnectProviderRequest(
                "nonexistent_service",
                "My Service",
                Map.of("key", "val"));

        assertThatThrownBy(() -> controller.connectProvider(req))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.fieldErrors()).anyMatch(fe -> fe.message().contains("Unknown provider type"));
                });
    }

    @Test
    @DisplayName("testConnection tests existing integration via workflow provider")
    void testConnectionWorkflowProvider() {
        UUID intId = UUID.randomUUID();
        Integration entity = Integration.createConnected(ORG_ID, USER_ID, "github", "GitHub", "1234");

        when(integrationService.getEntity(ADMIN_PRINCIPAL, intId)).thenReturn(entity);
        when(integrationService.getDecryptedCredentials(ADMIN_PRINCIPAL, intId))
                .thenReturn(new DecryptedCredentials(Map.of("token", "ghp_valid")));
        when(mockGitHub.testConnection(any(IntegrationContext.class)))
                .thenReturn(ConnectionTestResult.success("GitHub PAT valid"));

        var res = controller.testConnection(intId);
        assertThat(res.success()).isTrue();
        assertThat(res.message()).isEqualTo("GitHub PAT valid");
    }

    @Test
    @DisplayName("sendTest triggers test delivery for notification provider")
    void sendTestNotificationProvider() {
        UUID intId = UUID.randomUUID();
        Integration entity = Integration.createConnected(ORG_ID, USER_ID, "slack", "Slack", "1234");

        when(integrationService.getEntity(ADMIN_PRINCIPAL, intId)).thenReturn(entity);
        when(integrationService.getDecryptedCredentials(ADMIN_PRINCIPAL, intId))
                .thenReturn(new DecryptedCredentials(Map.of("webhookUrl", "https://hooks.slack.com/...")));
        when(mockSlack.sendTest(any(DeliveryContext.class)))
                .thenReturn(DeliveryResult.success(200, 150));

        var res = controller.sendTest(intId);
        assertThat(res.success()).isTrue();
        assertThat(res.httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("defaultDocsUrl provides valid documentation URLs for all key providers")
    void defaultDocsUrlReturnsValidUrls() {
        for (IntegrationType type : IntegrationType.values()) {
            String url = ProviderController.defaultDocsUrl(type);
            assertThat(url)
                    .as("Docs URL for %s must not be null or blank", type)
                    .isNotBlank()
                    .startsWith("http");
        }
    }
}
