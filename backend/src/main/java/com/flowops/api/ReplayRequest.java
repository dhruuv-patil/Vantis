package com.flowops.api;

import com.fasterxml.jackson.databind.JsonNode;

public record ReplayRequest(
        Boolean fromFailedNode,
        JsonNode modifiedInput,
        Boolean forceSideEffects
) {}
