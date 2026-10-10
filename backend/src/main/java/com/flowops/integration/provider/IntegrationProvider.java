package com.flowops.integration.provider;

import com.flowops.common.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared catalog and credential contract. Monitoring, delivery, and future action
 * providers retain separate execution interfaces: a CRM must not implement dummy
 * workflow discovery methods just to appear in the catalog.
 */
public interface IntegrationProvider {

    IntegrationType type();

    String displayName();

    String description();

    List<CredentialField> credentialFields();

    default ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, false, false, false, false, false);
    }

    /** Documentation / API setup URL for the provider. */
    default String docsUrl() {
        return null;
    }

    /** Reject undeclared fields before they can be copied into public metadata. */
    default void validateCredentialFields(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            throw ApiException.validation("config", "Credential configuration is required.");
        }
        Set<String> allowed = credentialFields().stream()
                .map(CredentialField::key).collect(Collectors.toSet());
        if (config.size() > allowed.size() || !allowed.containsAll(config.keySet())) {
            throw ApiException.validation("config", "Configuration contains an unsupported field.");
        }
        if (config.values().stream().anyMatch(value -> value == null || value.length() > 16384)) {
            throw ApiException.validation("config", "Credential fields must be strings of at most 16384 characters.");
        }
    }
}
