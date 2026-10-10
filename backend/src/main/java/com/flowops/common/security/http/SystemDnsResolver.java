package com.flowops.common.security.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Standard system DNS resolver using JDK {@link InetAddress#getAllByName(String)}.
 */
@Component
public class SystemDnsResolver implements DnsResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        return Arrays.asList(addresses);
    }
}
