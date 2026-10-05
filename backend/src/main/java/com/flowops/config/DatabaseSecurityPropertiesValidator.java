package com.flowops.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Validates that production PostgreSQL datasource connections require TLS.
 *
 * <p>Enforces defense in depth for production deployments:
 * <ol>
 *   <li>Only active when the {@code prod} Spring profile is active.</li>
 *   <li>Fails fast if {@code SPRING_DATASOURCE_URL} is missing or empty.</li>
 *   <li>Rejects insecure {@code sslmode} settings ({@code disable}, {@code allow}, {@code prefer}).</li>
 *   <li>Rejects explicit {@code ssl=false}.</li>
 *   <li>Requires secure {@code sslmode} ({@code require}, {@code verify-ca}, or {@code verify-full})
 *       either directly in the JDBC URL or via Hikari datasource properties.</li>
 * </ol>
 */
@Component
public class DatabaseSecurityPropertiesValidator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSecurityPropertiesValidator.class);

    private static final Set<String> INSECURE_SSL_MODES = Set.of("disable", "allow", "prefer");
    private static final Set<String> SECURE_SSL_MODES = Set.of("require", "verify-ca", "verify-full");

    private final Environment environment;
    private final String datasourceUrl;
    private final String sslModeProperty;
    private final String sslProperty;

    public DatabaseSecurityPropertiesValidator(
            Environment environment,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.hikari.data-source-properties.sslmode:}") String sslModeProperty,
            @Value("${spring.datasource.hikari.data-source-properties.ssl:}") String sslProperty) {
        this.environment = environment;
        this.datasourceUrl = datasourceUrl;
        this.sslModeProperty = sslModeProperty;
        this.sslProperty = sslProperty;
    }

    @PostConstruct
    public void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        validateConfiguration(datasourceUrl, sslModeProperty, sslProperty);
    }

    public static void validateConfiguration(String url, String configuredSslMode, String sslEnabled) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_URL is not set. A secure PostgreSQL connection string is required in production.");
        }

        // Only enforce for PostgreSQL datasources
        if (!url.startsWith("jdbc:postgresql:")) {
            return;
        }

        // Check for explicit ssl=false in properties or URL
        if ("false".equalsIgnoreCase(sslEnabled) || hasQueryParam(url, "ssl", "false")) {
            throw new IllegalStateException(
                    "Production PostgreSQL connections must not disable SSL (detected ssl=false). "
                            + "Production requires TLS encryption (sslmode=require minimum).");
        }

        // Check for explicitly insecure sslmode in URL or properties
        for (String insecureMode : INSECURE_SSL_MODES) {
            if (hasQueryParam(url, "sslmode", insecureMode)) {
                throw new IllegalStateException(
                        "Insecure PostgreSQL sslmode='" + insecureMode + "' detected in datasource URL. "
                                + "Production requires TLS encryption: sslmode=require, verify-ca, or verify-full.");
            }
            if (insecureMode.equalsIgnoreCase(configuredSslMode)) {
                throw new IllegalStateException(
                        "Insecure PostgreSQL sslmode='" + configuredSslMode + "' configured in datasource properties. "
                                + "Production requires TLS encryption: sslmode=require, verify-ca, or verify-full.");
            }
        }

        // Check for secure sslmode either in URL or in datasource properties
        boolean hasSecureUrlMode = false;
        for (String secureMode : SECURE_SSL_MODES) {
            if (hasQueryParam(url, "sslmode", secureMode)) {
                hasSecureUrlMode = true;
                break;
            }
        }

        boolean hasSecurePropertyMode = configuredSslMode != null
                && SECURE_SSL_MODES.contains(configuredSslMode.trim().toLowerCase(Locale.ROOT));

        if (!hasSecureUrlMode && !hasSecurePropertyMode) {
            throw new IllegalStateException(
                    "Production PostgreSQL connection must enforce TLS. Add 'sslmode=require' (or 'verify-full') "
                            + "to SPRING_DATASOURCE_URL or configure POSTGRES_SSL_MODE=require.");
        }
    }

    private static boolean hasQueryParam(String url, String key, String expectedValue) {
        int qIdx = url.indexOf('?');
        if (qIdx == -1 || qIdx == url.length() - 1) {
            return false;
        }
        String query = url.substring(qIdx + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                if (kv[1].trim().equalsIgnoreCase(expectedValue)) {
                    return true;
                }
            }
        }
        return false;
    }
}
