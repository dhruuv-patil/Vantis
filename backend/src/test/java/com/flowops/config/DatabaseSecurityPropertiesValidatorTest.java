package com.flowops.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

class DatabaseSecurityPropertiesValidatorTest {

    @Nested
    @DisplayName("Secure Configuration Acceptance")
    class SecureConfigurationTests {

        @Test
        @DisplayName("Accepts URL with sslmode=require")
        void acceptsUrlWithSslmodeRequire() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops?sslmode=require",
                    null,
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts URL with sslmode=verify-full")
        void acceptsUrlWithSslmodeVerifyFull() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://db.prod.internal:5432/flowops?sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca.pem",
                    null,
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts URL with sslmode=verify-ca")
        void acceptsUrlWithSslmodeVerifyCa() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://db.prod.internal:5432/flowops?sslmode=verify-ca",
                    null,
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts Hikari property with sslmode=require when URL lacks query params")
        void acceptsHikariPropertyWithRequire() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops",
                    "require",
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts Hikari property with sslmode=verify-full")
        void acceptsHikariPropertyWithVerifyFull() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops",
                    "verify-full",
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts uppercase sslmode in URL or properties")
        void acceptsCaseInsensitiveSslMode() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops?sslmode=REQUIRE",
                    null,
                    "true"
            )).doesNotThrowAnyException();

            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops",
                    "VERIFY-FULL",
                    "true"
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Accepts non-Postgres JDBC URL (e.g. H2 in memory)")
        void acceptsNonPostgresUrl() {
            assertThatCode(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:h2:mem:flowops-test;MODE=PostgreSQL",
                    null,
                    null
            )).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Insecure Configuration Rejection")
    class InsecureConfigurationTests {

        @ParameterizedTest
        @ValueSource(strings = {"disable", "allow", "prefer"})
        @DisplayName("Rejects insecure sslmode in URL")
        void rejectsInsecureSslModeInUrl(String mode) {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops?sslmode=" + mode,
                    null,
                    "true"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insecure PostgreSQL sslmode='" + mode + "' detected in datasource URL");
        }

        @ParameterizedTest
        @ValueSource(strings = {"disable", "allow", "prefer"})
        @DisplayName("Rejects insecure sslmode in Hikari properties")
        void rejectsInsecureSslModeInProperty(String mode) {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops",
                    mode,
                    "true"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insecure PostgreSQL sslmode='" + mode + "' configured in datasource properties");
        }

        @Test
        @DisplayName("Rejects explicit ssl=false in URL")
        void rejectsSslFalseInUrl() {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops?ssl=false&sslmode=require",
                    null,
                    "true"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("detected ssl=false");
        }

        @Test
        @DisplayName("Rejects explicit ssl=false in datasource property")
        void rejectsSslFalseInProperty() {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops?sslmode=require",
                    "require",
                    "false"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("detected ssl=false");
        }

        @Test
        @DisplayName("Rejects PostgreSQL URL without sslmode in prod")
        void rejectsMissingSslModeInUrlAndProperty() {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "jdbc:postgresql://postgres:5432/flowops",
                    null,
                    "true"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Production PostgreSQL connection must enforce TLS");
        }

        @Test
        @DisplayName("Rejects empty or missing SPRING_DATASOURCE_URL in prod")
        void rejectsEmptyDatasourceUrl() {
            assertThatThrownBy(() -> DatabaseSecurityPropertiesValidator.validateConfiguration(
                    "",
                    "require",
                    "true"
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_URL is not set");
        }
    }

    @Nested
    @DisplayName("Profile-Specific Activation")
    class ProfileActivationTests {

        @Test
        @DisplayName("Validator runs and fails fast when prod profile is active")
        void validatorFailsInProdProfile() {
            Environment env = mock(Environment.class);
            when(env.acceptsProfiles(Profiles.of("prod"))).thenReturn(true);

            DatabaseSecurityPropertiesValidator validator = new DatabaseSecurityPropertiesValidator(
                    env,
                    "jdbc:postgresql://postgres:5432/flowops?sslmode=disable",
                    null,
                    "true"
            );

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insecure PostgreSQL sslmode='disable'");
        }

        @Test
        @DisplayName("Validator skips validation when non-prod profile (e.g. dev/test) is active")
        void validatorSkipsInNonProdProfile() {
            Environment env = mock(Environment.class);
            when(env.acceptsProfiles(Profiles.of("prod"))).thenReturn(false);

            DatabaseSecurityPropertiesValidator validator = new DatabaseSecurityPropertiesValidator(
                    env,
                    "jdbc:postgresql://localhost:5432/flowops", // unencrypted local dev DB
                    null,
                    null
            );

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }
}
