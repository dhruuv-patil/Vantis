package com.flowops.common.security.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HardenedHttpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer localServer;
    private static int localServerPort;

    @BeforeAll
    static void startLocalServer() throws IOException {
        localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServerPort = localServer.getAddress().getPort();

        localServer.createContext("/ok", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        localServer.createContext("/echo-headers", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] response = ("{\"auth\":\"" + (authHeader != null ? authHeader : "") + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        localServer.createContext("/redirect-loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        localServer.createContext("/redirect-to-metadata", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        localServer.createContext("/redirect-to-other-host", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + localServerPort + "/echo-headers");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        localServer.createContext("/huge-payload", exchange -> {
            byte[] chunk = new byte[8192];
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 200; i++) { // ~1.6MB
                    os.write(chunk);
                    os.flush();
                }
            }
        });

        localServer.start();
    }

    @AfterAll
    static void stopLocalServer() {
        if (localServer != null) {
            localServer.stop(0);
        }
    }

    private HardenedHttpClient createClient(SsrfProperties props, DnsResolver resolver) {
        IpAddressValidator ipValidator = new IpAddressValidator(props);
        UrlValidator urlValidator = new UrlValidator(props, ipValidator);
        return new HardenedHttpClient(props, urlValidator, resolver, ipValidator, MAPPER);
    }

    private HardenedHttpClient createDefaultClient() {
        SsrfProperties props = new SsrfProperties();
        return createClient(props, new SystemDnsResolver());
    }

    @Test
    void blocksCloudMetadataIpv4() {
        HardenedHttpClient client = createDefaultClient();
        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://169.254.169.254/latest/meta-data/")
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void blocksAwsImdsV6() {
        HardenedHttpClient client = createDefaultClient();
        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://[fd00:ec2::254]/latest/meta-data/")
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void blocksLocalhostVariants() {
        HardenedHttpClient client = createDefaultClient();

        for (String url : List.of(
                "http://127.0.0.1:8080/api",
                "http://localhost:8080/api",
                "http://127.0.0.2:8080/api",
                "http://[::1]:8080/api")) {
            HardenedHttpRequest req = HardenedHttpRequest.builder().get(url).build();
            assertThatThrownBy(() -> client.execute(req))
                    .as("Should block localhost variant: " + url)
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void blocksPrivateRfc1918Ranges() {
        HardenedHttpClient client = createDefaultClient();

        for (String url : List.of(
                "http://10.0.0.1:8080/api",
                "http://172.16.5.10:8080/api",
                "http://172.31.255.255:8080/api",
                "http://192.168.1.1:8080/api")) {
            HardenedHttpRequest req = HardenedHttpRequest.builder().get(url).build();
            assertThatThrownBy(() -> client.execute(req))
                    .as("Should block RFC 1918 range: " + url)
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void blocksIpv4MappedAndCompatibleIpv6() {
        HardenedHttpClient client = createDefaultClient();

        for (String url : List.of(
                "http://[::ffff:127.0.0.1]:8080/api",
                "http://[::ffff:10.0.0.1]:8080/api",
                "http://[::ffff:a00:1]:8080/api",
                "http://[64:ff9b::127.0.0.1]:8080/api",
                "http://[2002:7f00:1::]:8080/api")) {
            HardenedHttpRequest req = HardenedHttpRequest.builder().get(url).build();
            assertThatThrownBy(() -> client.execute(req))
                    .as("Should block IPv6 mapped/compatible: " + url)
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void blocksAlternateIpEncodings() {
        HardenedHttpClient client = createDefaultClient();

        for (String url : List.of(
                "http://2130706433:8080/api",      // 127.0.0.1 in decimal
                "http://0x7f000001:8080/api",      // 127.0.0.1 in hex
                "http://0177.0.0.1:8080/api",      // 127.0.0.1 with octal leading zero
                "http://127.1:8080/api",           // 127.0.0.1 short form
                "http://167772161:8080/api",       // 10.0.0.1 in decimal
                "http://0x7f.0.0.1:8080/api")) {
            HardenedHttpRequest req = HardenedHttpRequest.builder().get(url).build();
            assertThatThrownBy(() -> client.execute(req))
                    .as("Should block alternate IP encoding: " + url)
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void blocksDnsNamesResolvingToPrivateIps() {
        DnsResolver mockResolver = host -> List.of(InetAddress.getByName("10.0.1.5"));
        HardenedHttpClient client = createClient(new SsrfProperties(), mockResolver);

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://internal-payroll.corp.example.com/api")
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void dnsRebindingSimulatedFailsOnPinning() throws Exception {
        // First lookup returns public IP (our mock), but client pins to that IP and connects to it.
        // Even if subsequent DNS lookup returned 127.0.0.1, the socket was opened directly to the pinned IP!
        AtomicInteger resolutionCount = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");

        DnsResolver rebindingResolver = host -> {
            int count = resolutionCount.incrementAndGet();
            if (count == 1) {
                return List.of(publicAddress);
            } else {
                return List.of(privateAddress);
            }
        };

        HardenedHttpClient client = createClient(new SsrfProperties(), rebindingResolver);

        // 8.8.8.8 won't have our HTTP server, so it will time out or refuse connection,
        // but it MUST NOT connect to 127.0.0.1!
        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://rebind-test.example.com:80/status")
                .timeout(Duration.ofMillis(300))
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isNotInstanceOf(SsrfBlockedException.class)
                .isInstanceOf(HardenedHttpException.class);
    }

    @Test
    void allowlistPermitsSpecifiedPrivateHost() throws Exception {
        SsrfProperties props = new SsrfProperties();
        props.getAllowlist().setHosts(List.of("127.0.0.1", "localhost"));
        props.getAllowlist().setCidrs(List.of("127.0.0.0/8"));
        props.getAllowedPorts().add(localServerPort);

        HardenedHttpClient client = createClient(props, new SystemDnsResolver());

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://127.0.0.1:" + localServerPort + "/ok")
                .build();

        HardenedHttpResponse resp = client.execute(req);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsString()).contains("\"status\":\"ok\"");
    }

    @Test
    void redirectChainExceedingHopLimitFails() {
        SsrfProperties props = new SsrfProperties();
        props.getAllowlist().setHosts(List.of("127.0.0.1"));
        props.getAllowedPorts().add(localServerPort);
        props.setMaxRedirects(2);

        HardenedHttpClient client = createClient(props, new SystemDnsResolver());

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://127.0.0.1:" + localServerPort + "/redirect-loop")
                .followRedirects(true)
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(TooManyRedirectsException.class);
    }

    @Test
    void redirectHopReValidationCatchesInternalIp() {
        SsrfProperties props = new SsrfProperties();
        props.getAllowlist().setHosts(List.of("127.0.0.1"));
        props.getAllowedPorts().add(localServerPort);

        HardenedHttpClient client = createClient(props, new SystemDnsResolver());

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://127.0.0.1:" + localServerPort + "/redirect-to-metadata")
                .followRedirects(true)
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void credentialsStrippedAcrossDifferentHostsOnRedirect() {
        SsrfProperties props = new SsrfProperties();
        props.getAllowlist().setHosts(List.of("127.0.0.1", "mock-first-host.local"));
        props.getAllowedPorts().add(localServerPort);

        // First host resolves to localServer, redirect location is http://127.0.0.1:.../echo-headers (different host string)
        DnsResolver resolver = host -> List.of(InetAddress.getByName("127.0.0.1"));
        HardenedHttpClient client = createClient(props, resolver);

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://mock-first-host.local:" + localServerPort + "/redirect-to-other-host")
                .header("Authorization", "Bearer sensitive-secret-token")
                .followRedirects(true)
                .build();

        HardenedHttpResponse resp = client.execute(req);
        assertThat(resp.statusCode()).isEqualTo(200);
        // Authorization header must have been stripped on the redirected request to 127.0.0.1!
        assertThat(resp.bodyAsString()).isEqualTo("{\"auth\":\"\"}");
    }

    @Test
    void oversizedResponseAbortsWithPayloadTooLargeException() {
        SsrfProperties props = new SsrfProperties();
        props.getAllowlist().setHosts(List.of("127.0.0.1"));
        props.getAllowedPorts().add(localServerPort);
        props.setMaxResponseBytes(64 * 1024); // Cap at 64 KB

        HardenedHttpClient client = createClient(props, new SystemDnsResolver());

        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get("http://127.0.0.1:" + localServerPort + "/huge-payload")
                .build();

        assertThatThrownBy(() -> client.execute(req))
                .isInstanceOf(PayloadTooLargeException.class);
    }
}
