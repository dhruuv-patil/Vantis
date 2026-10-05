package com.flowops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthAndCryptoPropertiesSecurityTest {

    @Nested
    @DisplayName("JWT Secret Validation")
    class JwtSecretValidationTests {

        @Test
        @DisplayName("Fails fast when JWT_SECRET is missing or empty")
        void failsWhenJwtSecretIsEmpty() {
            AuthProperties props = new AuthProperties(
                    "",
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", false, "Lax")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET is not set");
        }

        @Test
        @DisplayName("Fails fast when JWT_SECRET is shorter than 32 bytes")
        void failsWhenJwtSecretTooShort() {
            AuthProperties props = new AuthProperties(
                    "too-short-secret-only-24-b",
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", false, "Lax")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET is too short");
        }

        @Test
        @DisplayName("Fails fast when JWT_SECRET is the long known committed dev placeholder")
        void failsWhenJwtSecretIsLongDenylistedPlaceholder() {
            AuthProperties props = new AuthProperties(
                    "change-me-dev-secret-change-me-dev-secret",
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", false, "Lax")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("known placeholder value");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "change-me",
                "changeme",
                "secret",
                "dev-secret",
                "your-secret-here"
        })
        @DisplayName("Fails fast when short placeholder secrets are provided")
        void failsWhenShortPlaceholdersProvided(String shortPlaceholder) {
            AuthProperties props = new AuthProperties(
                    shortPlaceholder,
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", false, "Lax")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Fails fast when SameSite=None is configured without Secure=true")
        void failsWhenSameSiteNoneWithoutSecure() {
            AuthProperties props = new AuthProperties(
                    "a-strong-valid-secret-that-exceeds-32-bytes-in-length-for-hmac",
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", false, "None")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SameSite=None requires Secure=true");
        }

        @Test
        @DisplayName("Passes validation when JWT_SECRET is strong (>= 32 bytes) and cookies safe")
        void passesWhenValid() {
            AuthProperties props = new AuthProperties(
                    "a-strong-valid-secret-that-exceeds-32-bytes-in-length-for-hmac",
                    "flowops",
                    "flowops-api",
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofHours(1),
                    12,
                    new AuthProperties.RefreshCookie("flowops_refresh", "/api/auth", true, "None")
            );
            AuthPropertiesValidator validator = new AuthPropertiesValidator(props);

            assertThatCode(validator::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Encryption Key Validation")
    class EncryptionKeyValidationTests {

        @Test
        @DisplayName("Fails fast when ENCRYPTION_KEY is missing or empty")
        void failsWhenKeyIsEmpty() {
            CryptoProperties props = new CryptoProperties("");

            assertThatThrownBy(props::key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ENCRYPTION_KEY is not set");
        }

        @Test
        @DisplayName("Fails fast when ENCRYPTION_KEY is not valid Base64")
        void failsWhenNotBase64() {
            CryptoProperties props = new CryptoProperties("not-valid-base64-!@#$%^&*()");

            assertThatThrownBy(props::key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be valid base64");
        }

        @Test
        @DisplayName("Fails fast when ENCRYPTION_KEY decodes to wrong length (not 32 bytes)")
        void failsWhenWrongLength() {
            // 16 bytes encoded
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            CryptoProperties props = new CryptoProperties(shortKey);

            assertThatThrownBy(props::key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must decode to exactly 32 bytes (AES-256)");
        }

        @Test
        @DisplayName("Passes validation and returns exactly 32 bytes when key is valid")
        void passesWhenValidKey() {
            byte[] validRaw = new byte[32];
            for (int i = 0; i < 32; i++) {
                validRaw[i] = (byte) (i + 1);
            }
            String validKey = Base64.getEncoder().encodeToString(validRaw);
            CryptoProperties props = new CryptoProperties(validKey);

            byte[] decoded = props.key();
            assertThat(decoded).isEqualTo(validRaw);
            assertThat(decoded.length).isEqualTo(32);
        }
    }
}
