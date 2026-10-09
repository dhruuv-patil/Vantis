package com.flowops.common.security.http;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Validates outbound target URLs for scheme, userinfo, port, and canonical hostname.
 */
@Component
public class UrlValidator {

    private final SsrfProperties properties;
    private final IpAddressValidator ipValidator;

    public UrlValidator(SsrfProperties properties, IpAddressValidator ipValidator) {
        this.properties = properties;
        this.ipValidator = ipValidator;
    }

    /**
     * Parses and validates a URL string according to hardened SSRF rules.
     *
     * @param url raw target URL
     * @return validated and canonicalized URI
     * @throws SsrfBlockedException if the URL violates scheme, userinfo, port, or host rules
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new SsrfBlockedException("URL is required");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new SsrfBlockedException("URL has invalid syntax: " + e.getMessage());
        }

        // Scheme check: only http and https are allowed
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SsrfBlockedException("Only http and https schemes are permitted");
        }

        // UserInfo check: userinfo (username:password@) is forbidden in target URLs
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new SsrfBlockedException("User information in target URLs is forbidden");
        }

        // Host check
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new SsrfBlockedException("URL host is missing");
        }

        // IDN / Internationalized domain names normalization
        String canonicalHost;
        try {
            // Strip brackets for IPv6 before IDN parsing
            String hostToAscii = rawHost;
            if (hostToAscii.startsWith("[") && hostToAscii.endsWith("]")) {
                canonicalHost = hostToAscii.substring(1, hostToAscii.length() - 1);
            } else {
                canonicalHost = IDN.toASCII(hostToAscii);
            }
        } catch (Exception e) {
            throw new SsrfBlockedException("Invalid domain name: " + e.getMessage());
        }

        // Trailing dot check and canonicalization
        while (canonicalHost.endsWith(".")) {
            canonicalHost = canonicalHost.substring(0, canonicalHost.length() - 1);
        }

        if (canonicalHost.isBlank()) {
            throw new SsrfBlockedException("URL host is invalid");
        }

        // Port check: must be in allowed ports
        int port = uri.getPort();
        if (port == -1) {
            port = scheme.equalsIgnoreCase("https") ? 443 : 80;
        }

        if (properties.isEnabled() && properties.getAllowedPorts() != null && !properties.getAllowedPorts().isEmpty() && !properties.getAllowedPorts().contains(port)) {
            throw new SsrfBlockedException("Target port " + port + " is not permitted by outbound security policy");
        }

        // Validate the host string itself against internal/metadata rules
        ipValidator.validateHostString(canonicalHost);

        return uri;
    }

    /**
     * Returns the effective target port for a URI (defaulting to 80 for http and 443 for https).
     */
    public int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
