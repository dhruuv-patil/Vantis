package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Redis Set: writes a value by key to a Redis server via raw RESP protocol. */
@Component
public class RedisSetExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "redis_set";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String host = ctx.configString("host");
        String portStr = ctx.configString("port");
        String dbStr = ctx.configString("db");
        String password = ctx.configString("password");
        String key = ctx.configString("key");
        String value = ctx.configString("value");
        String ttlStr = ctx.configString("ttlSeconds");
        String mode = ctx.configString("mode");

        if (host == null || host.isBlank())
            return NodeResult.fail("Redis Set requires 'host'.");
        if (key == null || key.isBlank())
            return NodeResult.fail("Redis Set requires 'key'.");
        if (value == null)
            return NodeResult.fail("Redis Set requires 'value'.");

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

        int ttl = 0;
        if (ttlStr != null && !ttlStr.isBlank()) {
            try {
                ttl = Integer.parseInt(ttlStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String interpolatedKey = ctx.interpolate(key);
        String interpolatedValue = ctx.interpolate(value);
        String cmd = (mode == null || mode.isBlank() || "SET".equalsIgnoreCase(mode)) ? "SET" : mode.toUpperCase();

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

            if (password != null && !password.isBlank()) {
                sendCommand(writer, "AUTH", password);
                readResponse(reader);
            }

            if (db != 0) {
                sendCommand(writer, "SELECT", String.valueOf(db));
                readResponse(reader);
            }

            if ("SETEX".equals(cmd) && ttl > 0) {
                sendCommand(writer, "SETEX", interpolatedKey, String.valueOf(ttl), interpolatedValue);
            } else if ("SETNX".equals(cmd)) {
                sendCommand(writer, "SETNX", interpolatedKey, interpolatedValue);
            } else {
                sendCommand(writer, "SET", interpolatedKey, interpolatedValue);
                if (ttl > 0) {
                    readResponse(reader);
                    sendCommand(writer, "EXPIRE", interpolatedKey, String.valueOf(ttl));
                }
            }
            String response = readResponse(reader);

            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("key", interpolatedKey);
            output.put("ok", response.startsWith("+OK"));
            output.put("command", cmd);
            ctx.log().info("Redis " + cmd + " " + interpolatedKey + " → " + response);
            return NodeResult.success(output);

        } catch (Exception e) {
            return NodeResult.fail("Redis Set error: " + e.getMessage());
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
                return "$-1";
            char[] buf = new char[len];
            reader.read(buf, 0, len);
            reader.readLine();
            return new String(buf);
        } else if (line.startsWith("+") || line.startsWith("-") || line.startsWith(":")) {
            return line;
        }
        return line;
    }
}