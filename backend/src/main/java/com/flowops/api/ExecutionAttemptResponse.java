package com.flowops.api;

import com.flowops.domain.NodeExecutionAttempt;
import java.time.Instant;
import java.util.UUID;

public record ExecutionAttemptResponse(
        UUID id,
        UUID executionId,
        String nodeId,
        int attemptNumber,
        String status,
        String error,
        Long durationMs,
        Instant startedAt,
        Instant finishedAt) {

    public static ExecutionAttemptResponse of(NodeExecutionAttempt attempt) {
        return new ExecutionAttemptResponse(
                attempt.getId(),
                attempt.getExecutionId(),
                attempt.getNodeId(),
                attempt.getAttemptNumber(),
                attempt.getStatus(),
                attempt.getError(),
                attempt.getDurationMs(),
                attempt.getStartedAt(),
                attempt.getFinishedAt());
    }
}
