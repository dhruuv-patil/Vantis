package com.flowops.workflow.graph;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A directed connection from one node's output port to another node's input.
 * {@code sourceHandle} names the output port (e.g. {@code true}/{@code false} on
 * a condition); null means the node's single default output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphEdge(
        String id,
        String source,
        String target,
        @JsonAlias("source_handle") String sourceHandle,
        @JsonAlias("target_handle") String targetHandle) {
}
