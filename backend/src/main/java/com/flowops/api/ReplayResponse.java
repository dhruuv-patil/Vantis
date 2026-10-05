package com.flowops.api;

import java.util.List;
import java.util.UUID;

public record ReplayResponse(
        UUID newExecutionId,
        ExecutionDetailResponse execution,
        List<String> warnings,
        boolean requiresConfirmation
) {}
