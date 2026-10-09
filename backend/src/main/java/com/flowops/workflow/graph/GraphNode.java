package com.flowops.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A node in a workflow graph. {@code type} keys into the {@code NodeRegistry};
 * {@code config} holds the values captured by that type's config fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphNode(
        String id,
        String type,
        GraphPosition position,
        Map<String, Object> data,
        Map<String, Object> config) {

    public GraphNode(String id, String type, GraphPosition position, Map<String, Object> data) {
        this(id, type, position, data, null);
    }

    public Map<String, Object> data() {
        return data == null ? Map.of() : data;
    }

    /**
     * The config sub-map. The frontend nests captured field values under
     * {@code data.config}; a missing map means "nothing configured yet".
     * Also falls back to root {@code config} if present directly from AI output.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> config() {
        Object nestedConfig = data().get("config");
        if (nestedConfig instanceof Map && !((Map<?, ?>) nestedConfig).isEmpty()) {
            return (Map<String, Object>) nestedConfig;
        }
        if (config != null && !config.isEmpty()) {
            return config;
        }
        return nestedConfig instanceof Map ? (Map<String, Object>) nestedConfig : Map.of();
    }
}
