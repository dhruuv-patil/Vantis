package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Idempotency Check: reports if a key was already processed. */
@Component
public class IdempotencyCheckExecutor implements NodeExecutor {

    private static final ConcurrentHashMap<String, Boolean> seenKeys = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "idempotency_check";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String key = ctx.configString("key");
        if (key == null || key.isBlank()) {
            return NodeResult.fail("Idempotency Check requires a 'key'.");
        }

        String interpolatedKey = ctx.interpolate(key);
        boolean isDuplicate;
        if (ctx.idempotencyService() != null && ctx.organizationId() != null) {
            isDuplicate = ctx.idempotencyService().checkAndSet(
                    ctx.organizationId(),
                    ctx.executionId(),
                    ctx.node().id(),
                    interpolatedKey);
        } else {
            isDuplicate = seenKeys.putIfAbsent(interpolatedKey, true) != null;
        }

        ObjectNode output = ctx.mapper().createObjectNode();
        output.put("key", interpolatedKey);
        output.put("alreadyProcessed", isDuplicate);

        ctx.log().info("Idempotency Check: key=" + interpolatedKey + " → " + (isDuplicate ? "already processed" : "new") + ".");
        return NodeResult.success(output);
    }
}