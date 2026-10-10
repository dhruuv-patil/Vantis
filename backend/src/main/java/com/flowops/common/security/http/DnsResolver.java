package com.flowops.common.security.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Strategy interface for DNS resolution.
 * Allows deterministic testing of multi-address resolution, DNS failures, and DNS rebinding simulation.
 */
@FunctionalInterface
public interface DnsResolver {

    /**
     * Resolves all IP addresses for the given host.
     *
     * @param host hostname to resolve
     * @return list of resolved IP addresses
     * @throws UnknownHostException if host cannot be resolved
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
