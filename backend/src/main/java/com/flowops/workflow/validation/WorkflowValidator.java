package com.flowops.workflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.workflow.graph.GraphDocument;
import com.flowops.workflow.graph.GraphEdge;
import com.flowops.workflow.graph.GraphNode;
import com.flowops.workflow.nodes.ConfigField;
import com.flowops.workflow.nodes.NodeDefinition;
import com.flowops.workflow.nodes.NodeRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Hardened structural, semantic, dataflow, and security validation of a workflow graph.
 * Pure and side-effect free: it never touches the database, so it is reused unchanged
 * by the live "Validate" button, the publish gate, AI generation, and the pre-execution check.
 *
 * <p>Enforces that a workflow is a well-formed, single-entry, acyclic, secure, and
 * reliably executable pipeline of registered Vantis nodes.
 */
@Component
public class WorkflowValidator {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");
    private static final Pattern SK_SECRET_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_\\-]{20,}");
    private static final Pattern GHP_SECRET_PATTERN = Pattern.compile("ghp_[a-zA-Z0-9]{36}");
    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[a-zA-Z0-9_\\-.]{25,}\\b");

    private static final Pattern DANGEROUS_EXPR_PATTERN = Pattern.compile(
            "(?i)\\b(eval|exec|__import__|compile)\\s*\\(|\\b(os\\.|sys\\.|subprocess\\.|shutil\\.)|\\b(require|process\\.env|child_process)\\b|\\b(function\\s*\\(|new\\s+Function)|\\b(curl|wget|bash|sh|powershell|cmd)\\s+|[;|`&]\\s*(rm|cat|bash|sh)\\b");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "host.docker.internal", "169.254.169.254", "metadata.google.internal");

    private static final Set<String> MANDATORY_BRANCH_TYPES = Set.of(
            "condition", "if_else", "human_approval", "health_check", "circuit_breaker", "rate_limiter", "sla_monitor", "error_handler");

    private final NodeRegistry registry;
    private final ObjectMapper objectMapper;

    public WorkflowValidator(NodeRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(JsonNode graphJson) {
        List<ValidationIssue> issues = new ArrayList<>();

        GraphDocument graph;
        try {
            graph = objectMapper.treeToValue(graphJson, GraphDocument.class);
        } catch (Exception e) {
            return ValidationResult.of(List.of(ValidationIssue.graphError(
                    "GRAPH_UNREADABLE", "The workflow graph is malformed and could not be parsed.")));
        }

        List<GraphNode> nodes = graph.nodes();
        List<GraphEdge> edges = graph.edges();

        if (nodes.isEmpty()) {
            issues.add(ValidationIssue.graphError(
                    "EMPTY_GRAPH", "Add at least one trigger node to build a workflow."));
            return ValidationResult.of(issues);
        }

        // Index nodes by id and detect duplicates.
        Map<String, GraphNode> nodesById = new HashMap<>();
        for (GraphNode node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                issues.add(ValidationIssue.graphError("NODE_MISSING_ID", "A node is missing its id."));
                continue;
            }
            if (nodesById.putIfAbsent(node.id(), node) != null) {
                issues.add(ValidationIssue.nodeError(
                        "DUPLICATE_NODE_ID", "Two nodes share the id \"" + node.id() + "\".", node.id()));
            }
        }

        validateNodes(nodes, issues);
        int triggerCount = countTriggers(nodes);
        if (triggerCount == 0) {
            issues.add(ValidationIssue.graphError(
                    "NO_TRIGGER", "A workflow needs exactly one trigger node to start it."));
        } else if (triggerCount > 1) {
            issues.add(ValidationIssue.graphError(
                    "MULTIPLE_TRIGGERS", "A workflow can only have one trigger node."));
        }

        validateEdges(edges, nodesById, issues);
        detectReachability(nodes, edges, issues);
        detectCycle(nodesById.keySet(), edges, issues);
        validateTerminalPaths(nodes, edges, issues);
        validateDataflowAndExpressions(nodes, edges, nodesById, issues);
        validateSecurity(nodes, issues);

        return ValidationResult.of(issues);
    }

    private void validateNodes(List<GraphNode> nodes, List<ValidationIssue> issues) {
        for (GraphNode node : nodes) {
            if (node.id() == null) {
                continue;
            }
            NodeDefinition def = registry.find(node.type()).orElse(null);
            if (def == null) {
                issues.add(ValidationIssue.nodeError(
                        "UNKNOWN_NODE_TYPE",
                        "Unknown node type \"" + node.type() + "\".", node.id()));
                continue;
            }

            Map<String, Object> config = node.config();
            Set<String> declaredKeys = new HashSet<>();

            for (ConfigField field : def.configFields()) {
                declaredKeys.add(field.key());
                Object value = config.get(field.key());
                if (field.required()) {
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        issues.add(ValidationIssue.nodeError(
                                "MISSING_REQUIRED_CONFIG",
                                def.label() + " is missing required field \"" + field.label() + "\".",
                                node.id()));
                        continue;
                    }
                }

                if (value != null && "select".equals(field.type()) && field.options() != null && !field.options().isEmpty()) {
                    String strVal = String.valueOf(value);
                    if (!field.options().contains(strVal)) {
                        issues.add(ValidationIssue.nodeError(
                                "INVALID_CONFIG_ENUM",
                                "Field \"" + field.key() + "\" on node \"" + node.id() + "\" has invalid value \"" + strVal + "\". Allowed: " + field.options(),
                                node.id()));
                    }
                }
            }

            // Check for unsupported/invented configuration properties
            for (String key : config.keySet()) {
                if (!declaredKeys.contains(key)) {
                    issues.add(ValidationIssue.nodeError(
                            "UNSUPPORTED_CONFIG_KEY",
                            "Node \"" + node.id() + "\" (" + def.label() + ") declares unsupported configuration key \"" + key + "\".",
                            node.id()));
                }
            }
        }
    }

    private int countTriggers(List<GraphNode> nodes) {
        return (int) nodes.stream()
                .map(n -> registry.find(n.type()).orElse(null))
                .filter(d -> d != null && d.trigger())
                .count();
    }

    private void validateEdges(
            List<GraphEdge> edges, Map<String, GraphNode> nodesById, List<ValidationIssue> issues) {
        for (GraphEdge edge : edges) {
            if (edge.source() == null || edge.target() == null) {
                issues.add(ValidationIssue.edgeError(
                        "EDGE_INCOMPLETE", "A connection is missing an endpoint.", edge.id()));
                continue;
            }

            if (edge.source().equals(edge.target())) {
                issues.add(ValidationIssue.edgeError(
                        "SELF_LOOP_DETECTED", "Node \"" + edge.source() + "\" has a connection pointing to itself.", edge.id()));
            }

            if (!nodesById.containsKey(edge.source()) || !nodesById.containsKey(edge.target())) {
                issues.add(ValidationIssue.edgeError(
                        "EDGE_DANGLING", "A connection references a node that no longer exists.", edge.id()));
                continue;
            }

            GraphNode sourceNode = nodesById.get(edge.source());
            GraphNode targetNode = nodesById.get(edge.target());

            // Trigger cannot have an input
            registry.find(targetNode.type()).ifPresent(def -> {
                if (def.trigger()) {
                    issues.add(ValidationIssue.nodeError(
                            "TRIGGER_HAS_INPUT",
                            def.label() + " is a trigger and cannot have an incoming connection.",
                            targetNode.id()));
                }
            });

            // Validate source handles on branching nodes
            registry.find(sourceNode.type()).ifPresent(def -> {
                if (def.outputs() != null && def.outputs().size() > 1) {
                    if (edge.sourceHandle() == null || edge.sourceHandle().isBlank()) {
                        issues.add(ValidationIssue.edgeError(
                                "MISSING_SOURCE_HANDLE",
                                "Connection from branching node \"" + sourceNode.id() + "\" (" + def.label() + ") must specify a sourceHandle. Available: " + def.outputs(),
                                edge.id()));
                    } else if (!def.outputs().contains(edge.sourceHandle())) {
                        issues.add(ValidationIssue.edgeError(
                                "INVALID_SOURCE_HANDLE",
                                "Invalid sourceHandle \"" + edge.sourceHandle() + "\" from node \"" + sourceNode.id() + "\". Allowed outputs: " + def.outputs(),
                                edge.id()));
                    }
                }
            });
        }
    }

    /** Reachability analysis: every node must have an incoming path from a trigger. */
    private void detectReachability(
            List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Map<String, List<String>> adjacency = new HashMap<>();
        List<String> triggers = new ArrayList<>();

        for (GraphNode node : nodes) {
            adjacency.put(node.id(), new ArrayList<>());
            boolean isTrigger = registry.find(node.type()).map(NodeDefinition::trigger).orElse(false);
            if (isTrigger) {
                triggers.add(node.id());
            }
        }

        for (GraphEdge edge : edges) {
            if (edge.source() != null && edge.target() != null && adjacency.containsKey(edge.source())) {
                adjacency.get(edge.source()).add(edge.target());
            }
        }

        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(triggers);
        visited.addAll(triggers);

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            for (String next : adjacency.getOrDefault(curr, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        for (GraphNode node : nodes) {
            if (node.id() != null && !visited.contains(node.id())) {
                issues.add(ValidationIssue.nodeError(
                        "UNREACHABLE_NODE",
                        "Node \"" + node.id() + "\" has no incoming connection from the trigger and will never run.",
                        node.id()));
            }
        }
    }

    /** Kahn's algorithm: if any node remains after removing all zero-indegree nodes, there is a cycle. */
    private void detectCycle(Set<String> nodeIds, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String id : nodeIds) {
            indegree.put(id, 0);
            adjacency.put(id, new ArrayList<>());
        }
        for (GraphEdge edge : edges) {
            if (edge.source() == null || edge.target() == null) {
                continue;
            }
            if (!indegree.containsKey(edge.source()) || !indegree.containsKey(edge.target())) {
                continue;
            }
            adjacency.get(edge.source()).add(edge.target());
            indegree.merge(edge.target(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String next : adjacency.get(current)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < nodeIds.size()) {
            issues.add(ValidationIssue.graphError(
                    "CYCLE_DETECTED", "The workflow contains a loop; connections must not form a cycle."));
        }
    }

    /** Ensures mandatory branches are wired and paths terminate. */
    private void validateTerminalPaths(
            List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Map<String, Set<String>> outgoingHandles = new HashMap<>();
        Map<String, Integer> outgoingCount = new HashMap<>();

        for (GraphNode node : nodes) {
            outgoingHandles.put(node.id(), new HashSet<>());
            outgoingCount.put(node.id(), 0);
        }

        for (GraphEdge edge : edges) {
            if (edge.source() != null && edge.target() != null) {
                outgoingCount.merge(edge.source(), 1, Integer::sum);
                String h = edge.sourceHandle() == null ? "out" : edge.sourceHandle();
                outgoingHandles.computeIfAbsent(edge.source(), k -> new HashSet<>()).add(h);
            }
        }

        for (GraphNode node : nodes) {
            if (node.id() == null) continue;
            String type = node.type();
            if (MANDATORY_BRANCH_TYPES.contains(type)) {
                NodeDefinition def = registry.find(type).orElse(null);
                if (def != null && def.outputs() != null) {
                    for (String requiredOutput : def.outputs()) {
                        if (!outgoingHandles.getOrDefault(node.id(), Set.of()).contains(requiredOutput)) {
                            String code = "human_approval".equals(type) ? "APPROVAL_BRANCH_MISSING" : "MISSING_BRANCH_TARGET";
                            issues.add(ValidationIssue.nodeError(
                                    code,
                                    "Branching node \"" + node.id() + "\" (" + def.label() + ") is missing a connection for output branch \"" + requiredOutput + "\".",
                                    node.id()));
                        }
                    }
                }
            }
        }

        if (nodes.size() > 1) {
            long sinks = outgoingCount.values().stream().filter(c -> c == 0).count();
            if (sinks == 0) {
                issues.add(ValidationIssue.graphError(
                        "NO_TERMINAL_PATH", "The workflow has no terminal node. Every execution path must terminate."));
            }
        }
    }

    /** Validates variable dependencies and ensures referenced nodes are upstream ancestors. */
    private void validateDataflowAndExpressions(
            List<GraphNode> nodes, List<GraphEdge> edges, Map<String, GraphNode> nodesById, List<ValidationIssue> issues) {
        // Build predecessor map
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (GraphNode node : nodes) {
            predecessors.put(node.id(), new HashSet<>());
        }
        for (GraphEdge edge : edges) {
            if (edge.source() != null && edge.target() != null && predecessors.containsKey(edge.target())) {
                predecessors.get(edge.target()).add(edge.source());
            }
        }

        // Compute ancestors (transitive closure)
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (GraphNode node : nodes) {
            Set<String> anc = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>(predecessors.getOrDefault(node.id(), Set.of()));
            while (!stack.isEmpty()) {
                String p = stack.pop();
                if (anc.add(p)) {
                    stack.addAll(predecessors.getOrDefault(p, Set.of()));
                }
            }
            ancestors.put(node.id(), anc);
        }

        for (GraphNode node : nodes) {
            if (node.id() == null) continue;
            Map<String, Object> config = node.config();
            List<String> refs = extractVariables(config);
            Set<String> nodeAncestors = ancestors.getOrDefault(node.id(), Set.of());

            for (String ref : refs) {
                String cleanRef = ref.trim();
                String[] parts = cleanRef.split("\\.");
                if (parts.length == 0) continue;
                String first = parts[0].trim();

                String targetNodeId = null;
                if ("nodes".equals(first) && parts.length >= 2) {
                    targetNodeId = parts[1].trim();
                } else if ("trigger".equals(first) || "item".equals(first) || "loop".equals(first) || "env".equals(first)
                        || "secrets".equals(first) || "secret".equals(first) || "params".equals(first)
                        || "variables".equals(first) || "vars".equals(first) || "context".equals(first)
                        || "global".equals(first) || "globals".equals(first)
                        || parts.length == 1) {
                    continue;
                } else if (nodesById.containsKey(first)) {
                    targetNodeId = first;
                } else {
                    issues.add(ValidationIssue.nodeError(
                            "UNDEFINED_REFERENCE",
                            "Node \"" + node.id() + "\" references unknown entity or node \"" + first + "\" in {{" + cleanRef + "}}.",
                            node.id()));
                    continue;
                }

                if (targetNodeId != null) {
                    if (targetNodeId.equals(node.id())) {
                        issues.add(ValidationIssue.nodeError(
                                "SELF_REFERENCE",
                                "Node \"" + node.id() + "\" cannot reference its own output before executing.",
                                node.id()));
                    } else if (!nodeAncestors.contains(targetNodeId)) {
                        issues.add(ValidationIssue.nodeError(
                                "OUT_OF_SCOPE_REFERENCE",
                                "Node \"" + node.id() + "\" references \"" + targetNodeId + "\", which is not an upstream ancestor in this execution path.",
                                node.id()));
                    }
                }
            }

            // Check condition expressions for unsafe script execution
            if (config.containsKey("expression")) {
                Object exprObj = config.get("expression");
                if (exprObj instanceof String expr && DANGEROUS_EXPR_PATTERN.matcher(expr).find()) {
                    issues.add(ValidationIssue.nodeError(
                            "UNSAFE_EXPRESSION",
                            "Node \"" + node.id() + "\" contains unsafe code or script injection in expression.",
                            node.id()));
                }
            }
        }
    }

    private List<String> extractVariables(Object obj) {
        List<String> result = new ArrayList<>();
        if (obj instanceof String s) {
            Matcher m = VARIABLE_PATTERN.matcher(s);
            while (m.find()) {
                result.add(m.group(1));
            }
        } else if (obj instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                result.addAll(extractVariables(v));
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                result.addAll(extractVariables(item));
            }
        }
        return result;
    }

    /** Validates security policies: hardcoded secrets, SSRF URLs, retry counts, timeouts. */
    private void validateSecurity(List<GraphNode> nodes, List<ValidationIssue> issues) {
        for (GraphNode node : nodes) {
            if (node.id() == null) continue;
            Map<String, Object> config = node.config();

            // 1. Secrets scanning
            if (scanForSecrets(config)) {
                issues.add(ValidationIssue.nodeError(
                        "CREDENTIAL_LEAK_DETECTED",
                        "Node \"" + node.id() + "\" contains obvious hardcoded secrets or API keys. Use 'integrationId' instead.",
                        node.id()));
            }

            // 2. SSRF URL check
            for (String uf : List.of("url", "siteUrl", "uri")) {
                if (config.containsKey(uf) && config.get(uf) instanceof String urlStr && !urlStr.isBlank()) {
                    String cleanUrl = urlStr.trim().toLowerCase();
                    if (!cleanUrl.startsWith("{{")) {
                        for (String blocked : BLOCKED_HOSTS) {
                            if (cleanUrl.contains("://" + blocked) || cleanUrl.contains("@" + blocked)) {
                                issues.add(ValidationIssue.nodeError(
                                        "SSRF_TARGET_BLOCKED",
                                        "Node \"" + node.id() + "\" specifies an internal/private target URL (" + blocked + ") which is prohibited.",
                                        node.id()));
                                break;
                            }
                        }
                    }
                }
            }

            // 3. Retry limits
            if (config.containsKey("maxAttempts")) {
                try {
                    int attempts = Integer.parseInt(String.valueOf(config.get("maxAttempts")).trim());
                    if (attempts <= 0 || attempts > 10) {
                        issues.add(ValidationIssue.nodeError(
                                "INVALID_RETRY_POLICY",
                                "Node \"" + node.id() + "\" specifies maxAttempts=" + attempts + ". Must be between 1 and 10.",
                                node.id()));
                    }
                } catch (Exception e) {
                    issues.add(ValidationIssue.nodeError(
                            "INVALID_RETRY_POLICY",
                            "Node \"" + node.id() + "\" specifies an invalid maxAttempts value.",
                            node.id()));
                }
            }

            // 4. Timeout limits
            for (String tf : List.of("timeoutSeconds", "timeoutMs")) {
                if (config.containsKey(tf)) {
                    try {
                        double timeout = Double.parseDouble(String.valueOf(config.get(tf)).trim());
                        double limit = "timeoutMs".equals(tf) ? 3600000 : 3600;
                        if (timeout <= 0 || timeout > limit) {
                            issues.add(ValidationIssue.nodeError(
                                    "INVALID_TIMEOUT",
                                    "Node \"" + node.id() + "\" specifies an invalid timeout value (" + timeout + ").",
                                    node.id()));
                        }
                    } catch (Exception e) {
                        issues.add(ValidationIssue.nodeError(
                                "INVALID_TIMEOUT",
                                "Node \"" + node.id() + "\" specifies an invalid timeout value.",
                                node.id()));
                    }
                }
            }
        }
    }

    private boolean scanForSecrets(Object obj) {
        if (obj instanceof String s) {
            return SK_SECRET_PATTERN.matcher(s).find()
                    || GHP_SECRET_PATTERN.matcher(s).find()
                    || AWS_KEY_PATTERN.matcher(s).find()
                    || PRIVATE_KEY_PATTERN.matcher(s).find()
                    || BEARER_PATTERN.matcher(s).find();
        } else if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String k = String.valueOf(entry.getKey()).toLowerCase();
                if ((k.equals("password") || k.equals("apikey") || k.equals("api_key"))
                        && entry.getValue() instanceof String val
                        && !val.isBlank()
                        && !val.contains("{{")
                        && val.length() > 3
                        && !val.startsWith("conn-")) {
                    return true;
                }
                if (scanForSecrets(entry.getValue())) {
                    return true;
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (scanForSecrets(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
