package com.flowops.common.security.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Centralized, hardened outbound HTTP client for external integrations and webhooks.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Pre-connection DNS resolution and address validation for ALL resolved addresses (IPv4 & IPv6).</li>
 *   <li>DNS pinning to the validated address on socket connect, defeating DNS rebinding.</li>
 *   <li>SNI hostname preservation and TLS hostname verification on HTTPS connections.</li>
 *   <li>Blocking of loopback, RFC 1918 private, link-local / cloud metadata (169.254.169.254, AWS IMDSv6),
 *       CGNAT, unique-local IPv6, and alternate IP encodings.</li>
 *   <li>Deployment-level allowlist for self-hosted instances (default deny).</li>
 *   <li>Manual redirect following with hop limits, re-validating addresses on every hop, and
 *       stripping credentials across host boundaries.</li>
 *   <li>Connect / read timeouts, streaming response size caps, and safe redacted error logging.</li>
 * </ul>
 */
@Component
public class HardenedHttpClient {

    private static final Logger log = LoggerFactory.getLogger(HardenedHttpClient.class);

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "x-n8n-api-key", "cookie", "set-cookie", "proxy-authorization"
    );

    private final SsrfProperties properties;
    private final UrlValidator urlValidator;
    private final DnsResolver dnsResolver;
    private final IpAddressValidator ipValidator;
    private final ObjectMapper objectMapper;

    public HardenedHttpClient(
            SsrfProperties properties,
            UrlValidator urlValidator,
            DnsResolver dnsResolver,
            IpAddressValidator ipValidator,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.dnsResolver = dnsResolver;
        this.ipValidator = ipValidator;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a hardened HTTP request and returns the response.
     */
    public HardenedHttpResponse execute(HardenedHttpRequest request) {
        return executeWithRedirects(request, 0, null);
    }

    /**
     * Convenience method to execute a GET request and parse the response body as JSON.
     */
    public JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        HardenedHttpRequest req = HardenedHttpRequest.builder()
                .get(url)
                .headers(headers)
                .header("Accept", "application/json")
                .build();
        HardenedHttpResponse resp = execute(req);
        return resp.json(objectMapper);
    }

    private HardenedHttpResponse executeWithRedirects(
            HardenedHttpRequest request, int redirectHops, URI initialUri) {

        URI uri = urlValidator.validate(request.url());
        if (initialUri == null) {
            initialUri = uri;
        }

        // 1. Resolve DNS
        List<InetAddress> resolvedAddresses;
        try {
            resolvedAddresses = dnsResolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("Could not resolve target host");
        }

        if (resolvedAddresses == null || resolvedAddresses.isEmpty()) {
            throw new SsrfBlockedException("Target host resolved to no addresses");
        }

        // 2. Validate EVERY resolved address
        for (InetAddress addr : resolvedAddresses) {
            ipValidator.validateAddress(addr, uri.getHost());
        }

        // 3. Pin the first validated address
        InetAddress pinnedAddress = resolvedAddresses.get(0);
        int port = urlValidator.effectivePort(uri);
        boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());

        Duration connectTimeout = request.connectTimeout() != null
                ? request.connectTimeout()
                : properties.getConnectTimeout();
        Duration readTimeout = request.readTimeout() != null
                ? request.readTimeout()
                : properties.getReadTimeout();

        HardenedHttpResponse response;
        try {
            response = executeSingleRequest(request, uri, pinnedAddress, port, isHttps, connectTimeout, readTimeout);
        } catch (SocketTimeoutException e) {
            log.warn("Outbound HTTP request to {} timed out", sanitizeUrl(uri));
            throw new HardenedHttpException(ErrorCode.INTEGRATION_INVALID, "Target server timed out or failed to connect");
        } catch (IOException e) {
            log.warn("Outbound HTTP request to {} failed: {}", sanitizeUrl(uri), e.getClass().getSimpleName());
            throw new HardenedHttpException(ErrorCode.INTEGRATION_INVALID, "Connection failed: " + e.getMessage(), e);
        }

        // 4. Handle redirects manually if requested
        if (response.isRedirect() && request.followRedirects()) {
            if (redirectHops >= properties.getMaxRedirects()) {
                throw new TooManyRedirectsException(properties.getMaxRedirects());
            }

            String location = response.header("Location").orElse(null);
            if (location != null && !location.isBlank()) {
                URI nextUri = uri.resolve(location);
                boolean hostChanged = !nextUri.getHost().equalsIgnoreCase(uri.getHost());

                HardenedHttpRequest.Builder nextReqBuilder = HardenedHttpRequest.builder()
                        .url(nextUri.toString())
                        .followRedirects(true)
                        .connectTimeout(request.connectTimeout())
                        .readTimeout(request.readTimeout());

                // Copy headers, stripping sensitive auth credentials if cross-host
                for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                    if (hostChanged && SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                        continue; // Strip credential across hosts
                    }
                    nextReqBuilder.header(entry.getKey(), entry.getValue());
                }

                // If redirect is 303 (or 301/302 from POST), convert to GET and drop body
                if (response.statusCode() == 303 || ((response.statusCode() == 301 || response.statusCode() == 302) && "POST".equalsIgnoreCase(request.method()))) {
                    nextReqBuilder.method("GET");
                } else {
                    nextReqBuilder.method(request.method());
                    if (request.body() != null && request.body().length > 0) {
                        nextReqBuilder.body(request.body());
                    }
                }

                return executeWithRedirects(nextReqBuilder.build(), redirectHops + 1, initialUri);
            }
        }

        return response;
    }

    private HardenedHttpResponse executeSingleRequest(
            HardenedHttpRequest request,
            URI uri,
            InetAddress pinnedAddress,
            int port,
            boolean isHttps,
            Duration connectTimeout,
            Duration readTimeout) throws IOException {

        Socket rawSocket = new Socket();
        try {
            rawSocket.connect(new InetSocketAddress(pinnedAddress, port), (int) connectTimeout.toMillis());
            rawSocket.setSoTimeout((int) readTimeout.toMillis());

            Socket socketToUse = rawSocket;
            if (isHttps) {
                SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(
                        rawSocket, uri.getHost(), port, true);

                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(uri.getHost())));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(params);
                sslSocket.startHandshake();
                socketToUse = sslSocket;
            }

            try (Socket socket = socketToUse;
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                // Send request
                sendHttpRequest(request, uri, port, isHttps, out);

                // Read response
                return parseHttpResponse(in);
            }
        } catch (Exception e) {
            try {
                rawSocket.close();
            } catch (Exception ignored) {}
            if (e instanceof HardenedHttpException hhe) {
                throw hhe;
            }
            if (e instanceof SocketTimeoutException ste) {
                throw ste;
            }
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("HTTP execution error: " + e.getMessage(), e);
        }
    }

    private void sendHttpRequest(
            HardenedHttpRequest request,
            URI uri,
            int port,
            boolean isHttps,
            OutputStream out) throws IOException {

        String pathAndQuery = uri.getRawPath();
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            pathAndQuery = "/";
        }
        if (uri.getRawQuery() != null) {
            pathAndQuery += "?" + uri.getRawQuery();
        }

        StringBuilder req = new StringBuilder();
        req.append(request.method().toUpperCase(Locale.ROOT)).append(" ").append(pathAndQuery).append(" HTTP/1.1\r\n");

        String hostHeader = uri.getHost();
        if ((isHttps && port != 443) || (!isHttps && port != 80)) {
            hostHeader += ":" + port;
        }
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("User-Agent: Vantis-Reliability-Monitor/1.0\r\n");
        req.append("Connection: close\r\n");

        boolean hasContentType = false;
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            if ("host".equalsIgnoreCase(header.getKey()) || "connection".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            if ("content-type".equalsIgnoreCase(header.getKey())) {
                hasContentType = true;
            }
            req.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }

        byte[] body = request.body();
        if (body != null && body.length > 0) {
            if (!hasContentType) {
                req.append("Content-Type: application/json; charset=utf-8\r\n");
            }
            req.append("Content-Length: ").append(body.length).append("\r\n");
        }
        req.append("\r\n");

        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private HardenedHttpResponse parseHttpResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isBlank()) {
            throw new IOException("Remote server closed connection without an HTTP response");
        }

        int statusCode = parseStatusCode(statusLine);

        // Read headers
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String headerLine;
        while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon > 0) {
                String name = headerLine.substring(0, colon).trim();
                String value = headerLine.substring(colon + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }

        // Read body with maximum byte cap
        long maxBytes = properties.getMaxResponseBytes();
        boolean isChunked = isTransferEncodingChunked(headers);
        long contentLength = getContentLength(headers);

        byte[] body;
        if (isChunked) {
            body = readChunkedBody(in, maxBytes);
        } else if (contentLength >= 0) {
            if (contentLength > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            body = readFixedLengthBody(in, contentLength, maxBytes);
        } else {
            body = readEofBody(in, maxBytes);
        }

        return new HardenedHttpResponse(statusCode, headers, body);
    }

    private int parseStatusCode(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed HTTP status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HTTP status code in: " + statusLine);
        }
    }

    private boolean isTransferEncodingChunked(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("transfer-encoding".equalsIgnoreCase(entry.getKey())) {
                for (String v : entry.getValue()) {
                    if (v.toLowerCase(Locale.ROOT).contains("chunked")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private long getContentLength(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("content-length".equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                if (!values.isEmpty()) {
                    try {
                        return Long.parseLong(values.get(0).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    private byte[] readChunkedBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long totalRead = 0;

        while (true) {
            String line = readLine(in);
            if (line == null) {
                break;
            }
            String chunkSizeHex = line.trim();
            int semicolon = chunkSizeHex.indexOf(';');
            if (semicolon > 0) {
                chunkSizeHex = chunkSizeHex.substring(0, semicolon).trim();
            }
            if (chunkSizeHex.isEmpty()) {
                continue;
            }

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(chunkSizeHex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size hex: " + chunkSizeHex);
            }

            if (chunkSize == 0) {
                // Consume trailing headers / CRLF
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    // trailers ignored
                }
                break;
            }

            totalRead += chunkSize;
            if (totalRead > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }

            byte[] chunk = in.readNBytes(chunkSize);
            if (chunk.length < chunkSize) {
                throw new IOException("Premature EOF reading chunk data");
            }
            out.write(chunk);

            // Consume trailing CRLF of chunk
            readLine(in);
        }

        return out.toByteArray();
    }

    private byte[] readFixedLengthBody(InputStream in, long length, long maxBytes) throws IOException {
        if (length > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        byte[] buffer = in.readNBytes((int) length);
        if (buffer.length < length) {
            throw new IOException("Premature EOF reading fixed length body: expected " + length + ", got " + buffer.length);
        }
        return buffer;
    }

    private byte[] readEofBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String sanitizeUrl(URI uri) {
        if (uri == null) return "<null>";
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
    }
}
