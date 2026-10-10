package com.flowops.common.security;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.security.http.HardenedHttpException;
import com.flowops.common.security.http.IpAddressValidator;
import com.flowops.common.security.http.SsrfProperties;
import com.flowops.common.security.http.UrlValidator;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Destination policy for every server-side HTTP call a workflow can cause: the
 * {@code http_request} node, the {@code outbound_webhook} node, and
 * webhook-style
 * delivery. Blocks loopback, private/link-local ranges, unique-local IPv6, the
 * cloud-metadata range, and internal-looking hostnames before a socket is
 * opened.
 *
 * <p>
 * The check resolves every address the host maps to — a name that resolves to
 * any blocked address is rejected, not just the first. IP-literal URLs are
 * checked
 * without touching DNS. Known limitation (documented, not hidden): the
 * connection
 * re-resolves DNS at connect time, so a canonical defense would also pin the
 * validated address; a private-network allowlist for self-hosted callers is the
 * natural follow-up and deliberately does not exist yet.
 *
 * <p>
 * Failures are {@link ApiException} with {@link ErrorCode#INTEGRATION_INVALID}
 * and never echo the rejected URL — the query string may carry secrets.
 */
public final class SsrfGuard {

    private static final SsrfProperties DEFAULT_PROPERTIES = new SsrfProperties();
    private static final IpAddressValidator IP_VALIDATOR = new IpAddressValidator(DEFAULT_PROPERTIES);
    private static final UrlValidator URL_VALIDATOR = new UrlValidator(DEFAULT_PROPERTIES, IP_VALIDATOR);

    private SsrfGuard() {
    }

    /** Parses and validates {@code url}; returns the parsed URI on success. */
    public static URI validate(String url) {
        try {
            return URL_VALIDATOR.validate(url);
        } catch (HardenedHttpException e) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID, e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTEGRATION_INVALID, "The HTTP target URL is not a valid URI.");
        }
    }

    /**
     * Validates a bare host (database, Redis, Mongo targets) with the same policy
     * as
     * {@link #validate(String)}: no internal names, no address that resolves to a
     * private/loopback/link-local/metadata range.
     */
    public static void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "A target host is required.");
        }

        String trimmed = host.trim();

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || lower.endsWith(".svc")
                || lower.endsWith(".cluster.local")
                || (!lower.contains(".") && !lower.contains(":"))) {

            // A dotless name ("postgres", "redis", "ai-service")
            // is a compose/k8s service name.
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "Internal hostnames are not allowed as targets.");
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(trimmed)) {
                if (isBlocked(address)) {
                    throw new ApiException(
                            ErrorCode.INTEGRATION_INVALID,
                            "The target host resolves to a forbidden (private or local) address.");
                }
            }
        } catch (UnknownHostException unresolvable) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "The target host could not be resolved.");
        }
    }

    /**
     * A database name is interpolated into a JDBC URL, so it must not be able
     * to add URL parameters or path components.
     */
    public static void validateDatabaseName(String database) {
        if (database == null || !database.matches("[A-Za-z0-9_.\\-]{1,128}")) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "The database name contains characters that are not allowed.");
        }
    }

    /**
     * Validates every host in a mongodb:// connection string.
     * SRV URIs are refused because their DNS resolution can introduce
     * uncontrolled targets.
     */
    public static void validateMongoUri(String uri) {
        if (uri == null
                || !uri.trim().toLowerCase(Locale.ROOT).startsWith("mongodb://")) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "Only mongodb:// URIs are allowed.");
        }

        String rest = uri.trim().substring("mongodb://".length());

        int slash = rest.indexOf('/');
        int question = rest.indexOf('?');
        int end = rest.length();

        if (slash >= 0) {
            end = Math.min(end, slash);
        }

        if (question >= 0) {
            end = Math.min(end, question);
        }

        String authority = rest.substring(0, end);

        // Remove username/password from mongodb://user:password@host
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }

        if (authority.isBlank()) {
            throw new ApiException(
                    ErrorCode.INTEGRATION_INVALID,
                    "The MongoDB URI has no host.");
        }

        for (String hostPort : authority.split(",")) {
            String host = hostPort.trim();

            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                host = close > 0
                        ? host.substring(0, close + 1)
                        : host;
            } else {
                int colon = host.lastIndexOf(':');
                if (colon > 0) {
                    host = host.substring(0, colon);
                }
            }

            validateHost(host);
        }
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] octets = address.getAddress();

        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            int third = octets[2] & 0xFF;

            return blockedV4(first, second, third);
        }

        if (octets.length == 16) {
            int first = octets[0] & 0xFF;

            // IPv4-mapped IPv6 addresses: ::ffff:x.x.x.x
            boolean ipv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (octets[i] != 0) {
                    ipv4Mapped = false;
                    break;
                }
            }

            if (ipv4Mapped && octets[10] == (byte) 0xFF
                    && octets[11] == (byte) 0xFF) {
                int v4First = octets[12] & 0xFF;
                int v4Second = octets[13] & 0xFF;
                int v4Third = octets[14] & 0xFF;

                return blockedV4(v4First, v4Second, v4Third);
            }

            // fc00::/7 — IPv6 unique-local
            if ((first & 0xFE) == 0xFC) {
                return true;
            }

            // 2002::/16 — IPv6 6to4
            if (first == 0x20 && (octets[1] & 0xFF) == 0x02) {
                return true;
            }

            // 64:ff9b::/96 — NAT64 well-known prefix
            if ((octets[0] & 0xFF) == 0x00
                    && (octets[1] & 0xFF) == 0x64
                    && (octets[2] & 0xFF) == 0xFF
                    && (octets[3] & 0xFF) == 0x9B) {
                return true;
            }
        }

        return false;
    }

    private static boolean blockedV4(int first, int second, int third) {
        // 0.0.0.0/8
        if (first == 0)
            return true;

        // 10.0.0.0/8
        if (first == 10)
            return true;

        // 127.0.0.0/8
        if (first == 127)
            return true;

        // 100.64.0.0/10 — Carrier-grade NAT
        if (first == 100 && second >= 64 && second <= 127)
            return true;

        // 169.254.0.0/16 — link-local / cloud metadata
        if (first == 169 && second == 254)
            return true;

        // 172.16.0.0/12
        if (first == 172 && second >= 16 && second <= 31)
            return true;

        // 192.0.0.0/24 — IETF protocol assignments
        if (first == 192 && second == 0 && third == 0)
            return true;

        // 192.168.0.0/16
        if (first == 192 && second == 168)
            return true;

        // 198.18.0.0/15 — benchmarking
        if (first == 198 && (second == 18 || second == 19))
            return true;

        // 224.0.0.0/4 and above — multicast / reserved
        if (first >= 224)
            return true;

        return false;
    }
}
