package com.flowops.common.security.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-level configuration for SSRF protection and hardened outbound HTTP.
 *
 * <p>Private network allowlist is disabled by default (default deny). It can only be
 * configured at the deployment level (environment variables / application.yml), NEVER
 * via per-tenant user input, to safely allow self-hosted automation instances (e.g. n8n).
 */
@Component
@ConfigurationProperties(prefix = "flowops.security.ssrf")
public class SsrfProperties {

    private boolean enabled = true;
    private Allowlist allowlist = new Allowlist();
    private Set<Integer> allowedPorts = new HashSet<>(Set.of(80, 443, 8080, 8443, 5678));
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long maxResponseBytes = 5L * 1024L * 1024L; // 5 MB
    private int maxRedirects = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Allowlist getAllowlist() {
        return allowlist;
    }

    public void setAllowlist(Allowlist allowlist) {
        this.allowlist = allowlist;
    }

    public Set<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public void setAllowedPorts(Set<Integer> allowedPorts) {
        this.allowedPorts = allowedPorts;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public static class Allowlist {
        private boolean enabled = false;
        private List<String> allowedCidrs = new ArrayList<>();
        private List<String> allowedHosts = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedCidrs() {
            return allowedCidrs;
        }

        public void setAllowedCidrs(List<String> allowedCidrs) {
            this.allowedCidrs = allowedCidrs;
            if (allowedCidrs != null && !allowedCidrs.isEmpty()) {
                this.enabled = true;
            }
        }

        public void setCidrs(List<String> cidrs) {
            setAllowedCidrs(cidrs);
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts;
            if (allowedHosts != null && !allowedHosts.isEmpty()) {
                this.enabled = true;
            }
        }

        public void setHosts(List<String> hosts) {
            setAllowedHosts(hosts);
        }
    }
}
