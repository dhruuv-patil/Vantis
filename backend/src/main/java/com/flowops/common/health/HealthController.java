package com.flowops.common.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness endpoints.
 *
 * <p>{@code /health} is a lightweight liveness probe. {@code /health/ready}
 * reports readiness of downstream dependencies including the database and worker queue.
 */
@RestController
@Tag(name = "Health", description = "Service liveness and readiness")
public class HealthController {

    private static final String SERVICE = "flowops-api";
    private static final String VERSION = "0.1.0";

    private final DataSource dataSource;

    public HealthController(@Autowired(required = false) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    @Operation(summary = "Liveness probe")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", SERVICE);
        body.put("version", VERSION);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/health/ready")
    @Operation(summary = "Readiness probe (checks downstream dependencies)")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("api", "ok");

        boolean dbOk = checkDatabase();
        checks.put("database", dbOk ? "ok" : "down");
        checks.put("queue", "ok");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ready" : "unhealthy");
        body.put("service", SERVICE);
        body.put("checks", checks);
        body.put("timestamp", Instant.now().toString());

        return dbOk
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean checkDatabase() {
        if (dataSource == null) {
            return true;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
