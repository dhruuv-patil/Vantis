package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Redis Get: reads a value by key from a Redis server via raw RESP protocol.
 */
@Component
public class RedisGetExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "redis_get";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String host = ctx.configString("host");
        String portStr = ctx.configString("port");
        String dbStr = ctx.configString("db");
        String password = ctx.configString("password");
        String key = ctx.configString("key");

        if (host == null || host.isBlank())
            return NodeResult.fail("Redis Get requires 'host'.");
        if (key == null || key.isBlank())
            return NodeResult.fail("Redis Get requires 'key'.");

        int port = 6379;
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
        }

        int db = 0;
        if (dbStr != null && !dbStr.isBlank()) {
            try {
                db = Integer.parseInt(dbStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String interpolatedKey = ctx.interpolate(key);

        try {
            com.flowops.common.security.SsrfGuard.validateHost(host);
        } catch (com.flowops.common.error.ApiException blocked) {
            return NodeResult.fail("Redis target rejected: " + blocked.getMessage());
        }

        try (Socket socket = new Socket(host, port);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            // AUTH if password provided
            if (password != null && !password.isBlank()) {
                sendCommand(writer, "AUTH", password);
                readResponse(reader);
            }

            // SELECT DB
            if (db != 0) {
                sendCommand(writer, "SELECT", String.valueOf(db));
                readResponse(reader);
            }

            // GET key
            sendCommand(writer, "GET", interpolatedKey);
            String response = readResponse(reader);

            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("key", interpolatedKey);
            output.put("value", response);
            output.put("found", !response.equals("$-1"));
            ctx.log().info("Redis GET " + interpolatedKey + " → " + (response.equals("$-1") ? "nil" : "found"));
            return NodeResult.success(output);

        } catch (Exception e) {
            return NodeResult.fail("Redis Get error: " + e.getMessage());
        }
    }

    private void sendCommand(BufferedWriter writer, String... args) throws IOException {
        writer.write("*" + args.length + "\r\n");
        for (String arg : args) {
            writer.write("$" + arg.length() + "\r\n");
            writer.write(arg + "\r\n");
        }
        writer.flush();
    }

    private String readResponse(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null)
            return "";
        if (line.startsWith("$")) {
            int len = Integer.parseInt(line.substring(1));
            if (len == -1)
                return "$-1"; // nil
            char[] buf = new char[len];
            reader.read(buf, 0, len);
            reader.readLine(); // consume \r\n
            return new String(buf);
        } else if (line.startsWith("+") || line.startsWith("-") || line.startsWith(":")) {
            return line;
        }
        return line;
    }
}