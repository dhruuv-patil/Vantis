package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/** Dead Letter Queue: records a payload as undeliverable. */
@Component
public class DeadLetterQueueExecutor implements NodeExecutor {

    private final com.flowops.execution.DeadLetterService deadLetterService;

    @org.springframework.beans.factory.annotation.Autowired
    public DeadLetterQueueExecutor(com.flowops.execution.DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    public DeadLetterQueueExecutor() {
        this(null);
    }

    @Override
    public String type() {
        return "dead_letter_queue";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String payloadPath = ctx.configString("payloadPath");
        String reason = ctx.configString("reason");

        JsonNode payload = ctx.mapper().createObjectNode();
        if (payloadPath != null && !payloadPath.isBlank()) {
            JsonNode val = ctx.resolve(payloadPath);
            if (!val.isMissingNode()) {
                payload = val.deepCopy();
            }
        }

        ObjectNode output = ctx.mapper().createObjectNode();
        output.set("payload", payload);
        output.put("reason", reason == null ? "Undeliverable" : ctx.interpolate(reason));
        output.put("recordedAt", java.time.Instant.now().toString());

        ctx.log().warn("Dead Letter Queue recorded: " + output.get("reason").asText());
        return NodeResult.success(output);
    }
}