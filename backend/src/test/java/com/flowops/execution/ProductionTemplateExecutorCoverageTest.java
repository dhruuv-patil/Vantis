package com.flowops.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.workflow.templates.TemplateCatalog;
import com.flowops.workflow.templates.WorkflowTemplate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class ProductionTemplateExecutorCoverageTest {

    @Autowired
    private NodeExecutorRegistry executorRegistry;

    @Autowired
    private TemplateCatalog templateCatalog;

    @Test
    void everyProductionTemplateNodeHasARealRegisteredExecutor() {

        Set<String> nodeTypes = new HashSet<>();

        for (WorkflowTemplate template : templateCatalog.all()) {
            JsonNode nodes = template.graph().path("nodes");

            assertThat(nodes.isArray())
                    .as("%s must contain a nodes array", template.slug())
                    .isTrue();

            nodes.forEach(node -> {
                String type = node.path("type").asText();

                assertThat(type)
                        .as("%s contains a node without a type", template.slug())
                        .isNotBlank();

                nodeTypes.add(type);
            });
        }

        assertThat(nodeTypes)
                .as("Production templates must contain node types")
                .isNotEmpty();

        for (String type : nodeTypes) {
            assertThat(executorRegistry.find(type))
                    .as("No real executor registered for production node type '%s'", type)
                    .isPresent();
        }
    }
}