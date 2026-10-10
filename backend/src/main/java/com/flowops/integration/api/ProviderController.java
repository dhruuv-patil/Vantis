package com.flowops.integration.api;

import com.flowops.api.IntegrationResponse;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
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
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider onboarding API for BOTH provider families:
 *
 * <ul>
 *   <li><b>Workflow providers</b> (n8n, Make, Zapier, GitHub): external automation
 *       platforms whose executions FlowOps syncs for reliability monitoring.</li>
 *   <li><b>Delivery (notification) providers</b> (Slack, SMTP, Discord, Teams,
 *       Outbound webhook): channels a workflow's {@code notification} node routes
 *       messages through at run time.</li>
 * </ul>
 *
 * Provider-specific form schemas come from each provider's own
 * {@code credentialFields()} — no hard-coded provider logic lives here.
 *
 * The generic provider endpoints live at /api/integrations/providers to avoid
 * conflicting with the legacy /api/integrations path owned by
 * IntegrationController, which continues to serve the Slack-only connect flow.
 */
@RestController
@RequestMapping("/api/integrations")
@Tag(name = "Integrations — Providers")
public class ProviderController {

    private final IntegrationService integrationService;
    private final WorkflowProviderRegistry workflowProviders;
    private final NotificationProviderRegistry notificationProviders;
    private final CredentialCipher cipher;
    private final com.flowops.integration.provider.IntegrationRegistry registry;

    public ProviderController(
            IntegrationService integrationService,
            WorkflowProviderRegistry workflowProviders,
            NotificationProviderRegistry notificationProviders,
            CredentialCipher cipher,
            com.flowops.integration.provider.IntegrationRegistry registry) {
        this.integrationService = integrationService;
        this.workflowProviders = workflowProviders;
        this.notificationProviders = notificationProviders;
        this.cipher = cipher;
        this.registry = registry;
    }

    @GetMapping("/providers")
    @Operation(summary = "List available providers (workflow + delivery channels) for onboarding")
    public ProvidersResponse listProviders() {
        List<ProviderInfo> infos = new ArrayList<>();

        for (var provider : registry.all()) {
            ProviderCapabilities capabilities = provider instanceof WorkflowProvider workflow
                    ? workflow.capabilities()
                    : new ProviderCapabilities(false, false, false, false, false, false);
            String docsUrl = provider.docsUrl();
            if (docsUrl == null || docsUrl.isBlank()) {
                docsUrl = defaultDocsUrl(provider.type());
            }
            infos.add(new ProviderInfo(
                    provider.type().wire(),
                    provider.displayName(),
                    provider.description(),
                    true,
                    capabilities,
                    provider.credentialFields(),
                    docsUrl));
        }

        return new ProvidersResponse(infos);
    }

    @PostMapping(
            value = "/providers",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Connect a provider integration (workflow provider or delivery channel)")
    public IntegrationResponse connectProvider(
            @Valid @RequestBody ConnectProviderRequest request) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);

        IntegrationType type = IntegrationType.fromWire(request.type());
        if (type == null) {
            throw ApiException.validation("type", "Unknown provider type: " + request.type());
        }

        IntegrationProvider genericProvider = registry.require(type.wire());
        genericProvider.validateCredentialFields(request.config());
        DecryptedCredentials credentials = new DecryptedCredentials(request.config());

        ConnectionTestResult testResult = null;
        List<CredentialField> credFields = genericProvider.credentialFields();

        try {
            if (notificationProviders.supports(type)) {
                // Validate before storing: test the connection with the submitted config.
                NotificationProvider provider = notificationProviders.get(type);
                testResult = provider.testConnection(
                        new DeliveryContext(null, principal.organizationId(),
                                copyOf(request.config()), credentials));
            } else if (genericProvider instanceof AiProvider ai) {
                testResult = ai.testConnection(
                        new IntegrationContext(null, principal.organizationId(), credentials));
            } else if (genericProvider instanceof WorkflowProvider workflow) {
                testResult = workflow.testConnection(
                        new IntegrationContext(null, principal.organizationId(), credentials));
            } else {
                testResult = ConnectionTestResult.success("Connected");
            }
        } catch (ApiException ae) {
            throw ae;
        } catch (Exception e) {
            testResult = ConnectionTestResult.failure(
                    e.getMessage() != null && !e.getMessage().isBlank()
                            ? e.getMessage()
                            : "Connection test failed");
        }

        if (testResult != null && !testResult.success()) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID, testResult.message());
        }

        return persist(principal, type, request, secretKeys(credFields));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test stored credentials for an integration (workflow or delivery)")
    public ConnectionTestResponse testConnection(@PathVariable UUID id) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);

        Integration integration = integrationService.getEntity(principal, id);
        IntegrationType type = IntegrationType.fromWire(integration.getType());
        if (type == null) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID,
                    "Not an integration of a known provider");
        }

        DecryptedCredentials credentials =
                integrationService.getDecryptedCredentials(principal, id);

        if (notificationProviders.supports(type)) {
            NotificationProvider provider = notificationProviders.get(type);
            DeliveryContext context = new DeliveryContext(
                    id, principal.organizationId(), metadata(integration), credentials);
            var result = provider.testConnection(context);
            return new ConnectionTestResponse(result.success(), result.message());
        }

        IntegrationProvider provider = registry.get(type);
        if (provider instanceof AiProvider ai) {
            var result = ai.testConnection(new IntegrationContext(
                    id, principal.organizationId(), credentials));
            return new ConnectionTestResponse(result.success(), result.message());
        }

        if (provider instanceof WorkflowProvider workflow) {
            var result = workflow.testConnection(new IntegrationContext(
                    id, principal.organizationId(), credentials));
            return new ConnectionTestResponse(result.success(), result.message());
        }

        return new ConnectionTestResponse(true, "Connected");
    }

    @PostMapping("/{id}/send-test")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Send a self-addressed test message through a delivery channel")
    public SendTestResponse sendTest(@PathVariable UUID id) {

        FlowOpsPrincipal principal = AuthenticatedUser.requireRole(Role.ADMIN);

        Integration integration = integrationService.getEntity(principal, id);
        IntegrationType type = IntegrationType.fromWire(integration.getType());
        if (type == null || !notificationProviders.supports(type)) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID,
                    "Only connected delivery channels can send a test message");
        }

        NotificationProvider provider = notificationProviders.get(type);
        DeliveryContext context = new DeliveryContext(
                id, principal.organizationId(), metadata(integration),
                integrationService.getDecryptedCredentials(principal, id));

        DeliveryResult result = provider.sendTest(context);
        return new SendTestResponse(
                result.success(),
                result.code(),
                result.message(),
                result.retryable(),
                result.durationMs(),
                result.httpStatus());
    }

    public static String defaultDocsUrl(IntegrationType type) {
        if (type == null) return null;
        return switch (type) {
            case SLACK -> "https://api.slack.com/messaging/webhooks";
            case N8N -> "https://docs.n8n.io/api/";
            case MAKE -> "https://www.make.com/en/help/tools/api";
            case ZAPIER -> "https://zapier.com/help/create/code-webhooks/trigger-zaps-from-webhooks";
            case GITHUB -> "https://github.com/settings/tokens";
            case GITLAB -> "https://gitlab.com/-/user_settings/personal_access_tokens";
            case DISCORD -> "https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks";
            case TEAMS -> "https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook";
            case TELEGRAM -> "https://core.telegram.org/bots/tutorial";
            case TWILIO -> "https://console.twilio.com/";
            case SENDGRID -> "https://app.sendgrid.com/settings/api_keys";
            case RESEND -> "https://resend.com/api-keys";
            case EMAIL -> "https://support.google.com/a/answer/176600";
            case WEBHOOK -> "https://en.wikipedia.org/wiki/Webhook";
            case JIRA -> "https://id.atlassian.com/manage-profile/security/api-tokens";
            case LINEAR -> "https://linear.app/settings/api";
            case ASANA -> "https://app.asana.com/0/my-apps";
            case HUBSPOT -> "https://app.hubspot.com/";
            case SALESFORCE -> "https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_tokens_scopes.htm";
            case PIPEDRIVE -> "https://app.pipedrive.com/settings/personal-preferences/api";
            case NOTION -> "https://www.notion.so/my-integrations";
            case SENTRY -> "https://sentry.io/settings/account/api/auth-tokens/";
            case PAGERDUTY -> "https://support.pagerduty.com/docs/api-access-keys";
            case VERCEL -> "https://vercel.com/account/tokens";
            case STRIPE -> "https://dashboard.stripe.com/apikeys";
            case OPENAI -> "https://platform.openai.com/api-keys";
            case ANTHROPIC -> "https://console.anthropic.com/settings/keys";
            case AWS -> "https://console.aws.amazon.com/iam/";
            case S3 -> "https://docs.aws.amazon.com/AmazonS3/latest/userguide/";
            case GOOGLE_SHEETS -> "https://console.cloud.google.com/apis/credentials";
            case REST_API, CUSTOM -> "https://swagger.io/docs/specification/authentication/";
            default -> "https://docs.flowforge.ai/integrations";
        };
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record ProvidersResponse(
            List<ProviderInfo> providers) {
    }

    public record ProviderInfo(
            String type,
            String name,
            String description,
            boolean available,
            ProviderCapabilities capabilities,
            List<CredentialField> credentialFields,
            String docsUrl) {
    }

    public record ConnectProviderRequest(
            String type,
            String name,
            Map<String, String> config) {

        public ConnectProviderRequest {
            if (type != null) {
                type = type.strip().toLowerCase();
            }
            if (name != null) {
                name = name.strip();
            }
        }
    }

    public record ConnectionTestResponse(
            boolean success,
            String message) {
    }

    public record SendTestResponse(
            boolean success,
            String code,
            String message,
            boolean retryable,
            long durationMs,
            Integer httpStatus) {
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Encrypts the submitted config and persists the connected integration. */
    private IntegrationResponse persist(
            FlowOpsPrincipal principal,
            IntegrationType type,
            ConnectProviderRequest request,
            Set<String> sensitiveKeys) {

        // Encrypt credentials before persistence.
        String ciphertext = cipher.encryptJson(request.config());

        // Keep only a non-sensitive hint for display: last 4 of the primary secret,
        // falling back to any configured secret-looking value.
        String hint = lastFourOfSecret(request.config(), sensitiveKeys);

        Integration integration = integrationService.connectProvider(
                principal,
                type,
                request.name() == null || request.name().isBlank()
                        ? type.wire() : request.name(),
                ciphertext,
                hint,
                request.config(),
                sensitiveKeys);

        return IntegrationResponse.of(integration);
    }

    private String lastFourOfSecret(Map<String, String> config, Set<String> sensitiveKeys) {
        for (String key : sensitiveKeys) {
            String value = config.get(key);
            if (value != null && !value.isBlank()) {
                return maskTail(value);
            }
        }
        for (String key : List.of("apiKey", "apiToken", "accessToken", "pat",
                "authPassword", "secret", "webhookUrl")) {
            String value = config.get(key);
            if (value != null && !value.isBlank()) {
                return maskTail(value);
            }
        }
        return "****";
    }

    private static String maskTail(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    /** Set of secret-flagged credential keys for a provider's connect form. */
    private static Set<String> secretKeys(List<CredentialField> fields) {
        Set<String> keys = new java.util.HashSet<>();
        for (CredentialField field : fields) {
            if (field.secret()) {
                keys.add(field.key());
            }
        }
        return keys;
    }

    private static Map<String, Object> metadata(Integration integration) {
        return integration.getMetadata() == null ? Map.of() : integration.getMetadata();
    }

    private static Map<String, Object> copyOf(Map<String, String> config) {
        return new java.util.LinkedHashMap<>(config);
    }
}