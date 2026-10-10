package com.flowops.integration;

import com.flowops.api.IntegrationEnvelopes;
import com.flowops.api.IntegrationResponse;
import com.flowops.audit.AuditService;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AuditAction;
import com.flowops.domain.Integration;
import com.flowops.domain.IntegrationCredential;
import com.flowops.integration.provider.DecryptedCredentials;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.WorkflowProviderRegistry;
import com.flowops.repository.IntegrationCredentialRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationService {

    private static final String SLACK_HOST = "hooks.slack.com";
    private static final String SLACK_PATH_PREFIX = "/services/";

    private final IntegrationRepository integrations;
    private final IntegrationCredentialRepository credentials;
    private final CredentialCipher cipher;
    private final AuditService audit;
    private final WorkflowProviderRegistry providerRegistry;
    private final com.flowops.billing.service.EntitlementService entitlementService;
    private final com.flowops.repository.IntegrationSyncStateRepository syncStateRepo;
    private final com.flowops.integration.sync.IntegrationCircuitBreaker circuitBreaker;

    @org.springframework.beans.factory.annotation.Autowired
    public IntegrationService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentials,
            CredentialCipher cipher,
            AuditService audit,
            WorkflowProviderRegistry providerRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.billing.service.EntitlementService entitlementService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.repository.IntegrationSyncStateRepository syncStateRepo,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.integration.sync.IntegrationCircuitBreaker circuitBreaker) {
        this.integrations = integrations;
        this.credentials = credentials;
        this.cipher = cipher;
        this.audit = audit;
        this.providerRegistry = providerRegistry;
        this.entitlementService = entitlementService;
        this.syncStateRepo = syncStateRepo;
        this.circuitBreaker = circuitBreaker;
    }

    public IntegrationService(
            IntegrationRepository integrations,
            IntegrationCredentialRepository credentials,
            CredentialCipher cipher,
            AuditService audit,
            WorkflowProviderRegistry providerRegistry) {
        this(integrations, credentials, cipher, audit, providerRegistry, null, null, null);
    }

    @Transactional(readOnly = true)
    public IntegrationEnvelopes.Integrations list(FlowOpsPrincipal principal) {
        List<IntegrationResponse> rows = integrations
                .findByOrganizationIdOrderByUpdatedAtDesc(
                        principal.organizationId())
                .stream()
                .map(IntegrationResponse::of)
                .toList();

        return new IntegrationEnvelopes.Integrations(rows);
    }

    @Transactional(readOnly = true)
    public IntegrationResponse get(
            FlowOpsPrincipal principal,
            UUID id) {
        return IntegrationResponse.of(require(principal, id));
    }

    @Transactional(readOnly = true)
    public Integration getEntity(
            FlowOpsPrincipal principal,
            UUID id) {
        return require(principal, id);
    }

    @Transactional
    public IntegrationResponse connect(
            FlowOpsPrincipal principal,
            ConnectIntegrationRequest request) {

        if (!Integration.TYPE_SLACK.equals(request.type())) {
            throw ApiException.validation(
                    "type",
                    "Unsupported integration type.");
        }

        String webhookUrl =
                validateSlackWebhookUrl(request.webhookUrl());

        String hint = lastFour(webhookUrl);

        Map<String, String> credentialMap = new LinkedHashMap<>();
        credentialMap.put("webhookUrl", webhookUrl);

        String ciphertext =
                cipher.encryptJson(credentialMap);

        Integration integration = integrations
                .findByOrganizationIdAndTypeAndName(
                        principal.organizationId(),
                        Integration.TYPE_SLACK,
                        displayName(Integration.TYPE_SLACK))
                .or(() -> integrations.findByOrganizationIdAndType(
                        principal.organizationId(),
                        Integration.TYPE_SLACK))
                .orElse(null);

        if (integration == null) {
            if (entitlementService != null) {
                entitlementService.assertCanConnectIntegration(principal.organizationId());
            }
            integration = Integration.createConnected(
                    principal.organizationId(),
                    principal.userId(),
                    Integration.TYPE_SLACK,
                    displayName(Integration.TYPE_SLACK),
                    hint);

            integrations.save(integration);

            credentials.save(
                    IntegrationCredential.create(
                            integration.getId(),
                            ciphertext));
        } else {
            integration.connect(
                    displayName(Integration.TYPE_SLACK),
                    hint);

            IntegrationCredential existing =
                    credentials
                            .findByIntegrationId(
                                    integration.getId())
                            .orElse(null);

            if (existing == null) {
                credentials.save(
                        IntegrationCredential.create(
                                integration.getId(),
                                ciphertext));
            } else {
                existing.updateCiphertext(ciphertext);
            }
        }

        audit.record(
                principal,
                AuditAction.INTEGRATION_CONNECTED,
                integration.getId().toString(),
                "Connected the Slack integration (••••"
                        + hint
                        + ").");

        return IntegrationResponse.of(integration);
    }

    @Transactional
    public void disconnect(
            FlowOpsPrincipal principal,
            UUID id) {

        Integration integration =
                require(principal, id);

        integration.disconnect();

        credentials.deleteByIntegrationId(
                integration.getId());

        audit.record(
                principal,
                AuditAction.INTEGRATION_DISCONNECTED,
                integration.getId().toString(),
                "Disconnected the "
                        + integration.getName()
                        + " integration and destroyed its stored credential.");
    }

    private Integration require(
            FlowOpsPrincipal principal,
            UUID id) {

        return integrations
                .findByIdAndOrganizationId(
                        id,
                        principal.organizationId())
                .orElseThrow(() ->
                        new ApiException(
                                ErrorCode.INTEGRATION_NOT_FOUND));
    }

    private String displayName(String type) {
        return Integration.TYPE_SLACK.equals(type)
                ? "Slack"
                : type;
    }

    private String validateSlackWebhookUrl(String webhookUrl) {
    URI uri;

    try {
        uri = new URI(webhookUrl);
    } catch (URISyntaxException notAUri) {
        throw new ApiException(ErrorCode.INTEGRATION_INVALID);
    }

    boolean valid =
            "https".equalsIgnoreCase(uri.getScheme())
                    && SLACK_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getRawPath() != null
                    && uri.getRawPath().startsWith(SLACK_PATH_PREFIX);

    if (!valid) {
        throw new ApiException(ErrorCode.INTEGRATION_INVALID);
    }

    return webhookUrl;
}
    private String lastFour(String value) {
        return value.length() <= 4
                ? value
                : value.substring(value.length() - 4);
    }

    @Transactional
    public Integration connectProvider(
            FlowOpsPrincipal principal,
            IntegrationType type,
            String name,
            String ciphertext,
            String hint,
            Map<String, String> config,
            Set<String> sensitiveKeys) {

        String resolvedName = (name != null && !name.isBlank()) ? name.strip() : displayName(type.wire());

        // Multi-instance resolution: match on (organization_id, type, name)
        Integration integration = integrations
                .findByOrganizationIdAndTypeAndName(
                        principal.organizationId(),
                        type.wire(),
                        resolvedName)
                .orElse(null);

        // Fallback for legacy single-instance row where name might match the provider wire string
        if (integration == null && resolvedName.equalsIgnoreCase(type.wire())) {
            integration = integrations
                    .findByOrganizationIdAndType(
                            principal.organizationId(),
                            type.wire())
                    .orElse(null);
        }

        Map<String, String> nonSensitive =
                nonSensitiveConfig(
                        config,
                        sensitiveKeys);

        if (integration == null) {
            // Check plan limits before creating a new instance
            if (entitlementService != null) {
                entitlementService.assertCanConnectIntegration(principal.organizationId());
            }

            integration = Integration.createConnected(
                    principal.organizationId(),
                    principal.userId(),
                    type.wire(),
                    resolvedName,
                    hint);

            if (nonSensitive != null) {
                integration.setMetadata(
                        new LinkedHashMap<>(nonSensitive));
            }

            integrations.save(integration);

            credentials.save(
                    IntegrationCredential.create(
                            integration.getId(),
                            ciphertext));
        } else {
            integration.connect(resolvedName, hint);
            if (integration.isDisabled()) {
                integration.enable();
            }

            if (nonSensitive != null) {
                integration.setMetadata(
                        new LinkedHashMap<>(nonSensitive));
            }

            IntegrationCredential existing =
                    credentials
                            .findByIntegrationId(
                                    integration.getId())
                            .orElse(null);

            if (existing == null) {
                credentials.save(
                        IntegrationCredential.create(
                                integration.getId(),
                                ciphertext));
            } else {
                existing.updateCiphertext(ciphertext);
            }
        }

        if (circuitBreaker != null) {
            circuitBreaker.reset(integration.getId());
        }

        audit.record(
                principal,
                AuditAction.INTEGRATION_CONNECTED,
                integration.getId().toString(),
                "Connected the "
                        + type.wire()
                        + " integration '"
                        + resolvedName
                        + "' (••••"
                        + hint
                        + ").");

        return integration;
    }

    @Transactional
    public IntegrationResponse disable(
            FlowOpsPrincipal principal,
            UUID id) {

        Integration integration = require(principal, id);
        integration.disable();
        integrations.save(integration);

        audit.record(
                principal,
                AuditAction.INTEGRATION_DISCONNECTED,
                integration.getId().toString(),
                "Paused monitoring for the "
                        + integration.getName()
                        + " integration.");

        return IntegrationResponse.of(integration);
    }

    @Transactional
    public IntegrationResponse enable(
            FlowOpsPrincipal principal,
            UUID id) {

        Integration integration = require(principal, id);
        integration.enable();
        integrations.save(integration);

        audit.record(
                principal,
                AuditAction.INTEGRATION_CONNECTED,
                integration.getId().toString(),
                "Resumed monitoring for the "
                        + integration.getName()
                        + " integration.");

        return IntegrationResponse.of(integration);
    }

    @Transactional(readOnly = true)
    public com.flowops.domain.IntegrationSyncState getSyncState(
            FlowOpsPrincipal principal,
            UUID id) {

        require(principal, id);
        if (syncStateRepo == null) {
            return null;
        }
        return syncStateRepo
                .findByOrganizationIdAndIntegrationId(principal.organizationId(), id)
                .orElse(null);
    }

    private static Map<String, String> nonSensitiveConfig(
            Map<String, String> config,
            Set<String> sensitiveKeys) {

        if (config == null) {
            return null;
        }

        if (sensitiveKeys == null
                || sensitiveKeys.isEmpty()) {
            return config;
        }

        Map<String, String> filtered =
                new LinkedHashMap<>(config);

        filtered.keySet()
                .removeAll(sensitiveKeys);

        return filtered;
    }

    @Transactional(readOnly = true)
    public DecryptedCredentials getDecryptedCredentials(
            FlowOpsPrincipal principal,
            UUID id) {

        Integration integration =
                require(principal, id);

        IntegrationCredential cred =
                credentials
                        .findByIntegrationId(
                                integration.getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "No credential for integration"));

        Map<String, String> decrypted =
                cipher.decryptToMap(
                        cred.getCiphertext());

        return new DecryptedCredentials(decrypted);
    }

    @Transactional
    public IntegrationResponse updateCredentials(
            FlowOpsPrincipal principal,
            UUID id,
            Map<String, String> credentialsMap) {

        Integration integration = require(principal, id);
        if (credentialsMap == null || credentialsMap.isEmpty()) {
            throw ApiException.validation("credentials", "Credentials map cannot be empty");
        }

        String ciphertext = cipher.encryptJson(credentialsMap);
        IntegrationCredential credential = credentials.findByIntegrationId(integration.getId())
                .orElse(null);

        if (credential == null) {
            credentials.save(IntegrationCredential.create(integration.getId(), ciphertext));
        } else {
            credential.updateCiphertext(ciphertext);
        }

        if (!integration.isConnected()) {
            integration.connect(integration.getName(), integration.getHint());
        }

        if (circuitBreaker != null) {
            circuitBreaker.reset(integration.getId());
        }

        audit.record(
                principal,
                AuditAction.INTEGRATION_CREDENTIAL_UPDATED,
                integration.getId().toString(),
                "Updated credentials for " + integration.getName() + " integration.");

        return IntegrationResponse.of(integration);
    }

    @Transactional(readOnly = true)
    public List<IntegrationController.IntegrationHealthSummary> getHealthSummaries(FlowOpsPrincipal principal) {
        List<Integration> list = integrations.findByOrganizationIdOrderByUpdatedAtDesc(principal.organizationId());
        List<IntegrationController.IntegrationHealthSummary> result = new ArrayList<>();
        for (Integration item : list) {
            com.flowops.domain.IntegrationSyncState state = syncStateRepo != null
                    ? syncStateRepo.findByOrganizationIdAndIntegrationId(principal.organizationId(), item.getId()).orElse(null)
                    : null;
            result.add(new IntegrationController.IntegrationHealthSummary(
                    item.getId(),
                    item.getName(),
                    item.getType(),
                    state != null ? state.getHealthStatus() : "NEW",
                    state != null ? state.getLastSuccessfulSyncAt() : null,
                    state != null ? state.getLastErrorCategory() : null,
                    item.isDisabled(),
                    state != null && state.isBackfilling(),
                    state != null ? state.getBackfillProgress() : 100));
        }
        return result;
    }
}