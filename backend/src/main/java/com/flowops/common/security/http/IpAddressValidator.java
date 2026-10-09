package com.flowops.common.security.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates IP addresses and hostnames against SSRF restrictions:
 * private ranges, loopback, link-local, cloud metadata, multicast, unique-local IPv6,
 * IPv4-mapped IPv6, and alternate IP encodings (decimal, octal, hex, dword).
 *
 * <p>Supports deployment-level allowlists for self-hosted instances (e.g. n8n).
 */
@Component
public class IpAddressValidator {

    private static final Logger log = LoggerFactory.getLogger(IpAddressValidator.class);

    private static final Pattern DECIMAL_IP_PATTERN = Pattern.compile("^\\d{1,10}$");
    private static final Pattern HEX_IP_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{1,8}$");
    private static final Pattern DOTTED_HEX_OCTAL_PATTERN = Pattern.compile("^(0[xX][0-9a-fA-F]+|0[0-7]+|\\d+)(\\.(0[xX][0-9a-fA-F]+|0[0-7]+|\\d+)){1,3}$");

    private final SsrfProperties properties;

    public IpAddressValidator(SsrfProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks whether an un-resolved hostname itself represents an internal host,
     * cloud metadata name, or alternate IP encoding.
     */
    public void validateHostString(String host) {
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("Target host cannot be empty");
        }

        String trimmed = host.trim();
        // Remove IPv6 enclosing brackets if present
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        // Canonicalize trailing dots
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        if (trimmed.isBlank()) {
            throw new SsrfBlockedException("Target host cannot be empty");
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Check if host is in deployment allowlist
        if (isHostAllowlisted(lower)) {
            log.warn("Allowing host {} via deployment-level SSRF allowlist", lower);
            return;
        }

        // Well-known metadata and internal hostnames
        if (lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.equals("metadata.google.internal")
                || lower.equals("instance-data")
                || lower.equals("metadata")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || lower.endsWith(".svc")
                || lower.endsWith(".cluster.local")
                || (!lower.contains(".") && !lower.contains(":") && !isIpLiteral(lower))) {
            throw new SsrfBlockedException("Access to internal, local, or cloud-metadata hostnames is forbidden");
        }

        // Check for alternate IP encodings (decimal, hex, octal, short dotted)
        InetAddress parsedEncodedIp = parseAlternateIpEncoding(lower);
        if (parsedEncodedIp != null) {
            validateAddress(parsedEncodedIp, host);
            return;
        }

        // Standard IP literal check (IPv4 or IPv6 literal)
        if (isIpLiteral(lower)) {
            try {
                InetAddress literal = InetAddress.getByName(lower);
                validateAddress(literal, host);
                return;
            } catch (UnknownHostException ignored) {
                // Not a valid standard IP literal
            }
        }
    }

    /**
     * Validates an IP address against forbidden ranges, consulting the deployment allowlist.
     */
    public void validateAddress(InetAddress address, String originalHost) {
        if (!properties.isEnabled()) {
            return;
        }

        // Check deployment allowlist first
        if (isAddressAllowlisted(address, originalHost)) {
            log.warn("Allowing private IP address {} for host {} via deployment-level SSRF allowlist", address.getHostAddress(), originalHost);
            return;
        }

        if (isBlocked(address)) {
            throw new SsrfBlockedException("The target host resolves to a forbidden (private, loopback, or metadata) address");
        }
    }

    /**
     * Returns true if the address belongs to any forbidden range.
     */
    public boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] octets = address.getAddress();

        if (octets.length == 4) {
            int b0 = octets[0] & 0xFF;
            int b1 = octets[1] & 0xFF;
            int b2 = octets[2] & 0xFF;
            int b3 = octets[3] & 0xFF;
            return isBlockedIpv4(b0, b1, b2, b3);
        }

        if (octets.length == 16) {
            return isBlockedIpv6(octets);
        }

        return true;
    }

    private boolean isBlockedIpv4(int b0, int b1, int b2, int b3) {
        // 0.0.0.0/8 — Current network
        if (b0 == 0) return true;

        // 10.0.0.0/8 — Private network (RFC 1918)
        if (b0 == 10) return true;

        // 100.64.0.0/10 — Shared address space / CGNAT (RFC 6598)
        if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;

        // 127.0.0.0/8 — Loopback
        if (b0 == 127) return true;

        // 169.254.0.0/16 — Link-local / Cloud metadata (RFC 3927)
        if (b0 == 169 && b1 == 254) return true;

        // 172.16.0.0/12 — Private network (RFC 1918)
        if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;

        // 192.0.0.0/24 — IETF Protocol Assignments
        if (b0 == 192 && b1 == 0 && b2 == 0) return true;

        // 192.0.2.0/24 — TEST-NET-1
        if (b0 == 192 && b1 == 0 && b2 == 2) return true;

        // 192.88.99.0/24 — 6to4 Relay Anycast (RFC 7526)
        if (b0 == 192 && b1 == 88 && b2 == 99) return true;

        // 192.168.0.0/16 — Private network (RFC 1918)
        if (b0 == 192 && b1 == 168) return true;

        // 198.18.0.0/15 — Network benchmark tests (RFC 2544)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;

        // 198.51.100.0/24 — TEST-NET-2
        if (b0 == 198 && b1 == 51 && b2 == 100) return true;

        // 203.0.113.0/24 — TEST-NET-3
        if (b0 == 203 && b1 == 0 && b2 == 113) return true;

        // 224.0.0.0/4 — Multicast
        if (b0 >= 224 && b0 <= 239) return true;

        // 240.0.0.0/4 — Reserved / Future use
        if (b0 >= 240) return true;

        // 255.255.255.255 — Broadcast
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return true;

        return false;
    }

    private boolean isBlockedIpv6(byte[] octets) {
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;

        // ::/128 (Unspecified) and ::1/128 (Loopback)
        boolean allZerosExceptLast = true;
        for (int i = 0; i < 15; i++) {
            if (octets[i] != 0) {
                allZerosExceptLast = false;
                break;
            }
        }
        if (allZerosExceptLast) {
            int last = octets[15] & 0xFF;
            if (last == 0 || last == 1) return true;
        }

        // IPv4-mapped IPv6 (::ffff:0:0/96)
        boolean isIpv4Mapped = true;
        for (int i = 0; i < 10; i++) {
            if (octets[i] != 0) { isIpv4Mapped = false; break; }
        }
        if (isIpv4Mapped && (octets[10] & 0xFF) == 0xFF && (octets[11] & 0xFF) == 0xFF) {
            int v4b0 = octets[12] & 0xFF;
            int v4b1 = octets[13] & 0xFF;
            int v4b2 = octets[14] & 0xFF;
            int v4b3 = octets[15] & 0xFF;
            return isBlockedIpv4(v4b0, v4b1, v4b2, v4b3);
        }

        // IPv4-compatible IPv6 (::0:0/96 deprecated)
        boolean isIpv4Compatible = true;
        for (int i = 0; i < 12; i++) {
            if (octets[i] != 0) { isIpv4Compatible = false; break; }
        }
        if (isIpv4Compatible && !(allZerosExceptLast && (octets[15] & 0xFF) <= 1)) {
            int v4b0 = octets[12] & 0xFF;
            int v4b1 = octets[13] & 0xFF;
            int v4b2 = octets[14] & 0xFF;
            int v4b3 = octets[15] & 0xFF;
            return isBlockedIpv4(v4b0, v4b1, v4b2, v4b3);
        }

        // 64:ff9b::/96 (NAT64 well-known prefix)
        if (b0 == 0x00 && b1 == 0x64 && (octets[2] & 0xFF) == 0xFF && (octets[3] & 0xFF) == 0x9B) {
            int v4b0 = octets[12] & 0xFF;
            int v4b1 = octets[13] & 0xFF;
            int v4b2 = octets[14] & 0xFF;
            int v4b3 = octets[15] & 0xFF;
            return isBlockedIpv4(v4b0, v4b1, v4b2, v4b3);
        }

        // 2002::/16 (6to4)
        if (b0 == 0x20 && b1 == 0x02) {
            int v4b0 = octets[2] & 0xFF;
            int v4b1 = octets[3] & 0xFF;
            int v4b2 = octets[4] & 0xFF;
            int v4b3 = octets[5] & 0xFF;
            return isBlockedIpv4(v4b0, v4b1, v4b2, v4b3);
        }

        // fc00::/7 (Unique-Local Address - ULA, covers fc00:: and fd00:: including AWS IMDSv6 [fd00:ec2::254])
        if ((b0 & 0xFE) == 0xFC) {
            return true;
        }

        // fe80::/10 (Link-Local Unicast)
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return true;
        }

        // fec0::/10 (Site-Local Unicast, deprecated RFC 3879)
        if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
            return true;
        }

        // ff00::/8 (Multicast)
        if (b0 == 0xFF) {
            return true;
        }

        return false;
    }

    private boolean isHostAllowlisted(String host) {
        if (!properties.getAllowlist().isEnabled()) {
            return false;
        }
        for (String allowed : properties.getAllowlist().getAllowedHosts()) {
            if (host.equalsIgnoreCase(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAddressAllowlisted(InetAddress address, String originalHost) {
        if (!properties.getAllowlist().isEnabled()) {
            return false;
        }
        if (originalHost != null && isHostAllowlisted(originalHost)) {
            return true;
        }
        for (String cidr : properties.getAllowlist().getAllowedCidrs()) {
            if (matchesCidr(address, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(InetAddress address, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                InetAddress cidrAddr = InetAddress.getByName(cidr);
                return address.equals(cidrAddr);
            }
            String baseIp = cidr.substring(0, slash);
            int prefixLen = Integer.parseInt(cidr.substring(slash + 1));
            InetAddress cidrAddr = InetAddress.getByName(baseIp);

            byte[] addrBytes = address.getAddress();
            byte[] cidrBytes = cidrAddr.getAddress();

            if (addrBytes.length != cidrBytes.length) {
                return false;
            }

            int fullBytes = prefixLen / 8;
            int remBits = prefixLen % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != cidrBytes[i]) return false;
            }

            if (remBits > 0 && fullBytes < addrBytes.length) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Invalid CIDR in allowlist: {}", cidr);
            return false;
        }
    }

    private boolean isIpLiteral(String host) {
        return host.matches("^[0-9.]+$") || host.contains(":");
    }

    /**
     * Attempts to parse alternate IP encodings:
     * - Pure decimal: e.g. "2130706433" -> 127.0.0.1
     * - Hex integer: e.g. "0x7f000001" -> 127.0.0.1
     * - Dotted octal/hex: e.g. "0177.0.0.1", "0x7f.0.0.1", "127.1"
     */
    public static InetAddress parseAlternateIpEncoding(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String clean = host.trim();

        // 1. Single integer representation (decimal e.g. 2130706433)
        if (DECIMAL_IP_PATTERN.matcher(clean).matches()) {
            try {
                long val = Long.parseLong(clean);
                if (val >= 0 && val <= 0xFFFFFFFFL) {
                    byte[] bytes = new byte[4];
                    bytes[0] = (byte) ((val >> 24) & 0xFF);
                    bytes[1] = (byte) ((val >> 16) & 0xFF);
                    bytes[2] = (byte) ((val >> 8) & 0xFF);
                    bytes[3] = (byte) (val & 0xFF);
                    return InetAddress.getByAddress(bytes);
                }
            } catch (Exception ignored) {}
        }

        // 2. Single hex representation (e.g. 0x7f000001)
        if (HEX_IP_PATTERN.matcher(clean).matches()) {
            try {
                long val = Long.parseLong(clean.substring(2), 16);
                if (val >= 0 && val <= 0xFFFFFFFFL) {
                    byte[] bytes = new byte[4];
                    bytes[0] = (byte) ((val >> 24) & 0xFF);
                    bytes[1] = (byte) ((val >> 16) & 0xFF);
                    bytes[2] = (byte) ((val >> 8) & 0xFF);
                    bytes[3] = (byte) (val & 0xFF);
                    return InetAddress.getByAddress(bytes);
                }
            } catch (Exception ignored) {}
        }

        // 3. Dotted notation with octal, hex, or short forms (e.g. "127.1", "0177.0.0.1", "0x7f.1")
        if (DOTTED_HEX_OCTAL_PATTERN.matcher(clean).matches()) {
            String[] parts = clean.split("\\.");
            if (parts.length >= 1 && parts.length <= 4) {
                try {
                    long[] nums = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        String p = parts[i];
                        if (p.startsWith("0x") || p.startsWith("0X")) {
                            nums[i] = Long.parseLong(p.substring(2), 16);
                        } else if (p.startsWith("0") && p.length() > 1) {
                            nums[i] = Long.parseLong(p.substring(1), 8);
                        } else {
                            nums[i] = Long.parseLong(p, 10);
                        }
                    }

                    byte[] bytes = new byte[4];
                    if (parts.length == 4) {
                        for (int i = 0; i < 4; i++) {
                            if (nums[i] < 0 || nums[i] > 255) return null;
                            bytes[i] = (byte) nums[i];
                        }
                    } else if (parts.length == 2) {
                        // Class A network notation: A.B (e.g. 127.1 -> 127.0.0.1)
                        if (nums[0] < 0 || nums[0] > 255) return null;
                        if (nums[1] < 0 || nums[1] > 0xFFFFFFL) return null;
                        bytes[0] = (byte) nums[0];
                        bytes[1] = (byte) ((nums[1] >> 16) & 0xFF);
                        bytes[2] = (byte) ((nums[1] >> 8) & 0xFF);
                        bytes[3] = (byte) (nums[1] & 0xFF);
                    } else if (parts.length == 3) {
                        // Class B network notation: A.B.C (e.g. 127.0.1 -> 127.0.0.1)
                        if (nums[0] < 0 || nums[0] > 255) return null;
                        if (nums[1] < 0 || nums[1] > 255) return null;
                        if (nums[2] < 0 || nums[2] > 0xFFFFL) return null;
                        bytes[0] = (byte) nums[0];
                        bytes[1] = (byte) nums[1];
                        bytes[2] = (byte) ((nums[2] >> 8) & 0xFF);
                        bytes[3] = (byte) (nums[2] & 0xFF);
                    } else {
                        return null;
                    }
                    return InetAddress.getByAddress(bytes);
                } catch (Exception ignored) {}
            }
        }

        return null;
    }
}
