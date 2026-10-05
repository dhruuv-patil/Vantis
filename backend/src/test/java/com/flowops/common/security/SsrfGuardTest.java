package com.flowops.common.security;

import com.flowops.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hermetic tests for the SSRF destination policy. No Mockito, no network:
 * IP-literal
 * targets are checked without DNS, so every blocked case is provable
 * in-process.
 */
class SsrfGuardTest {

    @Test
    void acceptsPublicIpLiteralTargets() {
        // Public literals need no DNS, so this is hermetic even in a sandboxed CI.
        // (93.184.216.34 is example.com's historic address; any public v4 works.)
        assertThat(SsrfGuard.validate("https://93.184.216.34/get").getHost())
                .isEqualTo("93.184.216.34");
        assertThat(SsrfGuard.validate("http://1.1.1.1").getScheme()).isEqualTo("http");
        // Port and path must survive validation untouched.
        assertThat(SsrfGuard.validate("https://93.184.216.34:8443/a/b").getPort()).isEqualTo(8443);
    }

    @Test
    void blocksLoopbackAndLocalhostNames() {
        for (String target : new String[] {
                "http://localhost/x", "http://localhost:8080/admin", "http://127.0.0.1/",
                "http://[::1]/", "http://metadata.localhost/" }) {
            assertThatThrownBy(() -> SsrfGuard.validate(target))
                    .as("target " + target)
                    .isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("127.0.0.1"); // never echo the rejected URL
        }
    }

    @Test
    void blocksPrivateAndLinkLocalRanges() {
        for (String target : new String[] {
                "http://10.0.0.5/", "http://172.16.0.1/", "http://172.31.255.255/",
                "http://192.168.1.10/", "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0/" }) {
            assertThatThrownBy(() -> SsrfGuard.validate(target))
                    .as("target " + target)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void blocksIPv6UniqueLocalAndNonHttpSchemes() {
        assertThatThrownBy(() -> SsrfGuard.validate("http://[fc00::1]/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("http://[fd12::1]/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("file:///etc/passwd")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("ftp://example.com/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("gopher://example.com/")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsInternalLookingNamesAndMalformedUrls() {
        assertThatThrownBy(() -> SsrfGuard.validate("http://my-service.internal/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("http://printer.local/")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("not a url")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("http://")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfGuard.validate("   ")).isInstanceOf(ApiException.class);
    }

    @Test
    void blocksExtendedReservedRangesAndAlternateEncodings() {
        for (String target : new String[] {
                "http://100.64.0.1/",
                "http://100.100.100.200/",
                "http://198.18.0.1/",
                "http://192.0.0.1/",
                "http://240.0.0.1/",
                "http://255.255.255.255/",
                "http://[::ffff:127.0.0.1]/",
                "http://[::ffff:169.254.169.254]/",
                "http://[64:ff9b::7f00:1]/",
                "http://[2002:7f00:1::]/",
                "http://[fec0::1]/",
                "http://2130706433/",
                "http://0x7f000001/",
                "http://127.1/"
        }) {
            assertThatThrownBy(() -> SsrfGuard.validate(target))
                    .as("target " + target)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void validateHostBlocksComposeServiceNamesAndPrivateAddresses() {
        for (String host : new String[] {
                "postgres",
                "redis",
                "ai-service",
                "localhost",
                "10.0.0.5",
                "127.0.0.1",
                "[::1]",
                "db.internal",
                "cache.svc"
        }) {
            assertThatThrownBy(() -> SsrfGuard.validateHost(host))
                    .as("host " + host)
                    .isInstanceOf(ApiException.class);
        }

        SsrfGuard.validateHost("93.184.216.34");
    }

    @Test
    void validateDatabaseNameRejectsJdbcParameterInjection() {
        for (String name : new String[] {
                "app?autoDeserialize=true",
                "app&x=1",
                "a/b",
                "",
                "app;x"
        }) {
            assertThatThrownBy(() -> SsrfGuard.validateDatabaseName(name))
                    .as("db " + name)
                    .isInstanceOf(ApiException.class);
        }

        SsrfGuard.validateDatabaseName("customer_db-1.prod");
    }

    @Test
void validateMongoUriChecksEveryHostAndRefusesSrv() {
    assertThatThrownBy(() ->
            SsrfGuard.validateMongoUri(
                    "mongodb://10.0.0.5:27017/db"
            )
    ).isInstanceOf(ApiException.class);

    assertThatThrownBy(() ->
            SsrfGuard.validateMongoUri(
                    "mongodb://93.184.216.34,127.0.0.1/db"
            )
    ).isInstanceOf(ApiException.class);

    assertThatThrownBy(() ->
            SsrfGuard.validateMongoUri(
                    "mongodb+srv://cluster.example.com/db"
            )
    ).isInstanceOf(ApiException.class);

    assertThatThrownBy(() ->
            SsrfGuard.validateMongoUri(
                    "mongodb://user:pw@localhost/db"
            )
    ).isInstanceOf(ApiException.class);

    SsrfGuard.validateMongoUri(
            "mongodb://user:pw@93.184.216.34:27017/db?retryWrites=true"
    );
}
}
