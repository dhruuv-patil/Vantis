package com.flowops.workflow.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The built-in template gallery.
 *
 * <p>Every graph here is assembled from {@code NodeRegistry} node types with all
 * required config fields filled in, so a template lands in the builder ready to
 * validate and publish rather than as a broken skeleton the user has to repair.
 *
 * <p>Provider metadata is derived from the actual graph rather than guessed from
 * the template title or description. This keeps the Templates UI truthful and
 * allows it to render every provider supported by Vantis.
 *
 * <p>Graphs are built once at startup and handed out as {@code deepCopy()} by
 * {@link TemplateService}, so a caller can never mutate the shared instance.
 */
@Component
public class TemplateCatalog {

    private static final String PRODUCTION_TEMPLATE_PATTERN =
            "classpath*:templates/production/*.json";

    private final Map<String, WorkflowTemplate> bySlug = new LinkedHashMap<>();

    private final ObjectMapper mapper;

    public TemplateCatalog(ObjectMapper mapper) {
        this.mapper = mapper;

        // Core
        register(apiHealthCheck());
        register(webhookAiSummary());
        register(approvalBeforeAction());
        register(webhookTransformForward());
        register(delayedFollowUp());
        register(aiTriageInbound());

        // Reliability / Incident Management
        register(multiSystemDeploymentReconciliation());
        register(chaosExperimentOrchestrator());
        register(postIncidentReviewTracker());

        // Data / Document Operations
        register(multiSourceDataPipelineValidationReconciliation());
        register(automatedReportGenerationDistribution());
        register(documentProcessingEntityExtractionPipeline());

        // Business Operations
        register(employeeOnboardingOffboardingOrchestration());
        register(expenseApprovalPolicyValidation());
        register(vendorContractRenewalRiskAssessment());

        // NOTE: the following factories were referenced in the original constructor
        // but never defined. Either implement them here or ship them as JSON under
        // templates/production/ (loaded below, and JSON wins on slug clashes):
        //   intelligentTicketTriageAutoRouting, slaBreachDetectionEscalation,
        //   customerHealthScoreCalculationAlerting,
        //   knowledgeBaseGapDetectionArticleGeneration,
        //   accessRequestApprovalTimeBoundedGrants,
        //   vulnerabilityScanTriageRemediationTracking,
        //   complianceEvidenceCollectionAuditTrail,
        //   aiAgentCodeReviewOrchestrator, llmPromptEvaluationRegressionPipeline,
        //   autonomousLeadQualificationAgent

        loadProductionTemplates();
    }

    private void register(WorkflowTemplate template) {
        bySlug.put(template.slug(), template);
    }

    /* ------------------------------------------------- production templates */

    /**
     * Loads production-grade templates from JSON resources.
     *
     * <p>The JSON templates are loaded after the built-in Java templates.
     * If a JSON template uses the same slug as an existing Java template,
     * the JSON version becomes the active version in the catalog.
     */
    private void loadProductionTemplates() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver()
                            .getResources(PRODUCTION_TEMPLATE_PATTERN);

            List<Resource> ordered = new ArrayList<>(Arrays.asList(resources));

            ordered.sort(Comparator.comparing((Resource resource) -> {
                String filename = resource.getFilename();
                return filename == null ? "" : filename;
            }));

            for (Resource resource : ordered) {
                register(readProductionTemplate(resource));
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load Vantis production templates.", e);
        }
    }

    /**
     * Reads one production template JSON file and converts it into the
     * immutable WorkflowTemplate record used by Vantis.
     */
    private WorkflowTemplate readProductionTemplate(Resource resource) {

        try (InputStream inputStream = resource.getInputStream()) {

            JsonNode root = mapper.readTree(inputStream);

            String slug = requiredTemplateText(root, "slug", resource);
            String name = requiredTemplateText(root, "name", resource);
            String description = root.path("description").asText("");
            String category = requiredTemplateText(root, "category", resource);
            String icon = root.path("icon").asText("Workflow");
            List<String> tags = readTemplateStringArray(root.path("tags"));
            List<String> providers = readTemplateStringArray(root.path("providers"));
            JsonNode graph = root.get("graph");

            if (graph == null || graph.isNull() || !graph.isObject()) {
                throw new IllegalStateException(
                        "Production template " + resource.getFilename()
                                + " must contain an object-valued 'graph'.");
            }

            // The graph is the source of truth: fall back to it when the file
            // doesn't declare providers explicitly.
            if (providers.isEmpty()) {
                providers = extractProviders(graph);
            }

            return new WorkflowTemplate(
                    slug, name, description, category, icon,
                    tags, providers, graph);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read production template " + resource.getFilename(), e);
        }
    }

    private String requiredTemplateText(
            JsonNode root, String field, Resource resource) {

        JsonNode value = root.get(field);

        if (value == null || value.isNull()
                || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Production template " + resource.getFilename()
                            + " is missing required field '" + field + "'.");
        }

        return value.asText().strip();
    }

    private List<String> readTemplateStringArray(JsonNode node) {

        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();

        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().strip());
            }
        }

        return List.copyOf(values);
    }

    /** All templates in a stable, gallery-friendly order. */
    public List<WorkflowTemplate> all() {
        return List.copyOf(bySlug.values());
    }

    public Optional<WorkflowTemplate> find(String slug) {
        return Optional.ofNullable(slug == null ? null : bySlug.get(slug.strip()));
    }

    /* ------------------------------------------------------------ templates */

    private WorkflowTemplate apiHealthCheck() {
        Graph g = graph();

        g.node("trigger", "manual_trigger", "Run health check", Map.of(), 0, 1);

        g.node("check", "http_request", "Ping the endpoint",
                Map.of("method", "GET", "url", "https://example.com/health"),
                1, 1);

        g.node("gate", "condition", "Healthy?",
                Map.of("expression", "{{check.status}} == 200"),
                2, 1);

        g.node("ok", "notification", "Report healthy",
                Map.of("channel", "slack",
                        "message", "Health check passed with status {{check.status}}."),
                3, 0);

        g.node("alert", "notification", "Raise an alert",
                Map.of("channel", "slack",
                        "message", "Health check FAILED with status {{check.status}}."),
                3, 2);

        g.edge("trigger", "check", null);
        g.edge("check", "gate", null);
        g.edge("gate", "ok", "true");
        g.edge("gate", "alert", "false");

        return template(
                "api-health-check",
                "API health check with Slack alert",
                "Call an endpoint, branch on its status code, and post the outcome to Slack.",
                "Monitoring",
                "Activity",
                List.of("http", "monitoring", "slack", "alert"),
                g);
    }

    private WorkflowTemplate webhookAiSummary() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Inbound payload",
                Map.of("method", "POST"), 0, 1);

        g.node("summarize", "ai_agent", "Summarize the payload",
                Map.of("instructions",
                                "You summarize incoming JSON payloads in two sentences, "
                                        + "plainly and without speculation.",
                        "input", "{{trigger.body}}"),
                1, 1);

        g.node("post", "notification", "Share the summary",
                Map.of("channel", "slack",
                        "message", "New inbound payload:\n{{summarize.output}}"),
                2, 1);

        g.edge("trigger", "summarize", null);
        g.edge("summarize", "post", null);

        return template(
                "webhook-ai-summary",
                "Summarize an inbound webhook with AI",
                "Receive a webhook, have an AI agent summarize the payload, and post it to Slack.",
                "AI",
                "Sparkles",
                List.of("webhook", "ai", "summary", "slack"),
                g);
    }

    private WorkflowTemplate approvalBeforeAction() {
        Graph g = graph();

        g.node("trigger", "manual_trigger", "Start request", Map.of(), 0, 1);

        g.node("approval", "human_approval", "Wait for sign-off",
                Map.of("prompt", "Approve calling the downstream API for this request?"),
                1, 1);

        g.node("act", "http_request", "Perform the action",
                Map.of("method", "POST",
                        "url", "https://example.com/api/action",
                        "body", "{\"approved\": true}"),
                2, 0);

        g.node("declined", "notification", "Tell the requester",
                Map.of("channel", "email",
                        "message", "Your request was reviewed and not approved."),
                2, 2);

        g.edge("trigger", "approval", null);
        g.edge("approval", "act", "approved");
        g.edge("approval", "declined", "rejected");

        return template(
                "approval-before-action",
                "Human approval before an action",
                "Pause for a human decision, then either call the API or notify the requester.",
                "Approvals",
                "UserCheck",
                List.of("approval", "human", "gate", "http"),
                g);
    }

    private WorkflowTemplate webhookTransformForward() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Inbound payload",
                Map.of("method", "POST"), 0, 1);

        g.node("shape", "transform", "Reshape the fields",
                Map.of("mapping", Map.of(
                        "id", "{{trigger.body.id}}",
                        "email", "{{trigger.body.email}}")),
                1, 1);

        g.node("forward", "http_request", "Forward downstream",
                Map.of("method", "POST",
                        "url", "https://example.com/api/contacts",
                        "headers", Map.of("content-type", "application/json"),
                        "body", "{\"id\": \"{{shape.id}}\", \"email\": \"{{shape.email}}\"}"),
                2, 1);

        g.edge("trigger", "shape", null);
        g.edge("shape", "forward", null);

        return template(
                "webhook-transform-forward",
                "Reshape a webhook and forward it",
                "Take an inbound webhook, map its fields into a new shape, and POST it onward.",
                "Integrations",
                "Shuffle",
                List.of("webhook", "transform", "http", "integration"),
                g);
    }

    private WorkflowTemplate delayedFollowUp() {
        Graph g = graph();

        g.node("trigger", "manual_trigger", "Start follow-up", Map.of(), 0, 1);

        g.node("wait", "delay", "Wait a minute", Map.of("seconds", 60), 1, 1);

        g.node("fetch", "http_request", "Re-check the record",
                Map.of("method", "GET", "url", "https://example.com/api/records/1"),
                2, 1);

        g.node("tell", "notification", "Report the result",
                Map.of("channel", "slack",
                        "message", "Follow-up check returned status {{fetch.status}}."),
                3, 1);

        g.edge("trigger", "wait", null);
        g.edge("wait", "fetch", null);
        g.edge("fetch", "tell", null);

        return template(
                "delayed-follow-up",
                "Delayed follow-up check",
                "Wait a fixed period, re-read a remote record, and report what changed.",
                "Monitoring",
                "Clock",
                List.of("delay", "http", "follow-up", "slack"),
                g);
    }

    private WorkflowTemplate aiTriageInbound() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Inbound ticket",
                Map.of("method", "POST"), 0, 1);

        g.node("triage", "ai_agent", "Classify urgency",
                Map.of("instructions",
                                "Classify the ticket's urgency. Reply with exactly one word: "
                                        + "URGENT or NORMAL.",
                        "input", "{{trigger.body}}"),
                1, 1);

        g.node("gate", "condition", "Urgent?",
                Map.of("expression", "{{triage.output}} == URGENT"),
                2, 1);

        g.node("page", "notification", "Page the on-call",
                Map.of("channel", "slack",
                        "message", "URGENT ticket needs attention:\n{{trigger.body}}"),
                3, 0);

        g.node("queue", "notification", "Queue it normally",
                Map.of("channel", "email",
                        "message", "A new ticket was filed and queued for normal handling."),
                3, 2);

        g.edge("trigger", "triage", null);
        g.edge("triage", "gate", null);
        g.edge("gate", "page", "true");
        g.edge("gate", "queue", "false");

        return template(
                "ai-triage-inbound",
                "AI triage for inbound tickets",
                "Let an AI agent classify an inbound ticket, then page or queue it accordingly.",
                "AI",
                "Bot",
                List.of("ai", "triage", "webhook", "condition"),
                g);
    }

    /* ------------------------------------------------ reliability templates */

    private WorkflowTemplate multiSystemDeploymentReconciliation() {
        Graph g = graph();

        g.node("trigger", "manual_trigger", "Start reconciliation", Map.of(), 0, 1);

        g.node("fetch1", "http_request", "Fetch system 1 state",
                Map.of("method", "GET", "url", "https://system1.example.com/api/state"),
                1, 0);

        g.node("fetch2", "http_request", "Fetch system 2 state",
                Map.of("method", "GET", "url", "https://system2.example.com/api/state"),
                1, 1);

        g.node("fetch3", "http_request", "Fetch system 3 state",
                Map.of("method", "GET", "url", "https://system3.example.com/api/state"),
                1, 2);

        g.node("compare", "transform", "Compare states",
                Map.of("mapping", Map.of(
                        "system1", "{{fetch1.body}}",
                        "system2", "{{fetch2.body}}",
                        "system3", "{{fetch3.body}}",
                        "discrepancies",
                        "{{compareStates(fetch1.body, fetch2.body, fetch3.body)}}")),
                2, 1);

        g.node("detect", "condition", "Discrepancies found?",
                Map.of("expression", "{{compare.discrepancies.length}} > 0"),
                3, 1);

        g.node("alert", "notification", "Raise reconciliation alert",
                Map.of("channel", "slack",
                        "message", "Deployment state discrepancies detected: {{compare.discrepancies}}"),
                4, 0);

        g.node("reconcile", "http_request", "Reconcile systems",
                Map.of("method", "POST",
                        "url", "https://systems.example.com/api/reconcile",
                        "body", "{\"discrepancies\": {{compare.discrepancies}}}"),
                5, 0);

        g.node("confirm", "human_approval", "Confirm reconciliation",
                Map.of("prompt", "Confirm systems reconciliation?"),
                6, 0);

        g.node("notify", "notification", "Notify completion",
                Map.of("channel", "slack",
                        "message", "Deployment reconciliation completed."),
                7, 0);

        g.node("insync", "notification", "Report systems in sync",
                Map.of("channel", "slack",
                        "message", "All systems are in sync; no reconciliation needed."),
                4, 2);

        g.edge("trigger", "fetch1", null);
        g.edge("trigger", "fetch2", null);
        g.edge("trigger", "fetch3", null);
        g.edge("fetch1", "compare", null);
        g.edge("fetch2", "compare", null);
        g.edge("fetch3", "compare", null);
        g.edge("compare", "detect", null);
        g.edge("detect", "alert", "true");
        g.edge("detect", "insync", "false");
        g.edge("alert", "reconcile", null);
        g.edge("reconcile", "confirm", null);
        g.edge("confirm", "notify", "approved");

        return template(
                "multi-system-deployment-reconciliation",
                "Multi-System Deployment Reconciliation",
                "Compare state across multiple systems, detect discrepancies, and reconcile if needed.",
                "Reliability",
                "SyncAlt",
                List.of("http", "reconciliation", "monitoring", "approval", "slack"),
                g);
    }

    private WorkflowTemplate chaosExperimentOrchestrator() {
        Graph g = graph();

        g.node("trigger", "schedule_trigger", "Run chaos experiment",
                Map.of("cron", "0 9 * * 1-5", "timezone", "America/New_York"),
                0, 1);

        g.node("load", "http_request", "Load test",
                Map.of("method", "POST",
                        "url", "https://api.example.com/load-test",
                        "body", "{\"duration\": 300, \"users\": 1000}"),
                1, 0);

        g.node("stress", "http_request", "Stress test",
                Map.of("method", "POST",
                        "url", "https://api.example.com/stress-test",
                        "body", "{\"duration\": 600, \"load\": 2000}"),
                1, 2);

        g.node("monitor", "execution_monitor", "Monitor experiment",
                Map.of("statusPath", "{{load.status}}",
                        "durationPath", "{{load.duration}}"),
                2, 1);

        g.node("blast", "anomaly_detector", "Detect blast radius",
                Map.of("seriesPath", "{{monitor.metrics}}",
                        "zThreshold", 3.0),
                3, 1);

        g.node("gate", "condition", "Within blast radius?",
                Map.of("expression", "{{blast.anomalies.length}} == 0"),
                4, 1);

        g.node("abort", "stop_fail", "Abort experiment",
                Map.of("mode", "stop",
                        "message", "Chaos experiment exceeded blast radius."),
                5, 0);

        g.node("report", "notification", "Report results",
                Map.of("channel", "slack",
                        "message", "Chaos experiment completed: {{monitor.status}}"),
                5, 2);

        g.node("escalate", "escalation", "Escalate if needed",
                Map.of("title", "Chaos experiment results",
                        "body", "{{monitor.status}}",
                        "level", 1,
                        "channel", "slack"),
                6, 2);

        g.edge("trigger", "load", null);
        g.edge("trigger", "stress", null);
        g.edge("load", "monitor", null);
        g.edge("stress", "monitor", null);
        g.edge("monitor", "blast", null);
        g.edge("blast", "gate", null);
        g.edge("gate", "abort", "false");
        g.edge("gate", "report", "true");
        g.edge("report", "escalate", null);

        return template(
                "chaos-experiment-orchestrator",
                "Chaos Experiment Orchestrator with Blast Radius Control",
                "Run controlled chaos experiments with blast radius detection and automatic abort.",
                "Reliability",
                "ShieldCheck",
                List.of("chaos", "load-testing", "monitoring", "anomaly-detection", "slack"),
                g);
    }

    private WorkflowTemplate postIncidentReviewTracker() {
        Graph g = graph();

        g.node("trigger", "manual_trigger", "Start post-incident review", Map.of(), 0, 1);

        g.node("fetch", "http_request", "Fetch incident details",
                Map.of("method", "GET",
                        "url", "https://incidents.example.com/api/{{trigger.id}}"),
                1, 1);

        g.node("assess", "ai_agent", "Assess root causes",
                Map.of("instructions",
                                "Analyze incident details and identify root causes. Return a structured list.",
                        "input", "{{fetch.body}}"),
                2, 1);

        g.node("generate", "ai_agent", "Generate action items",
                Map.of("instructions",
                                "Based on root causes, generate action items with owners and deadlines.",
                        "input", "{{assess.output}}"),
                3, 1);

        g.node("review", "human_approval", "Review action items",
                Map.of("prompt", "Review the generated action items and confirm."),
                4, 1);

        g.node("store", "database_query", "Store review results",
                Map.of("databaseType", "postgresql",
                        "host", "db.example.com",
                        "database", "incidents",
                        "username", "reviewer",
                        "password", "{{trigger.secret}}",
                        "sql", "INSERT INTO incident_reviews (incident_id, root_causes, action_items) "
                                + "VALUES ('{{trigger.id}}', '{{assess.output}}', '{{generate.output}}')"),
                5, 1);

        g.node("notify", "notification", "Notify stakeholders",
                Map.of("channel", "slack",
                        "message", "Post-incident review completed for incident {{trigger.id}}."),
                6, 1);

        g.node("notify_rejected", "notification", "Notify rejection",
                Map.of("channel", "slack",
                        "message", "Post-incident review rejected for incident {{trigger.id}}."),
                5, 2);

        g.edge("trigger", "fetch", null);
        g.edge("fetch", "assess", null);
        g.edge("assess", "generate", null);
        g.edge("generate", "review", null);
        g.edge("review", "store", "approved");
        g.edge("review", "notify_rejected", "rejected");
        g.edge("store", "notify", null);

        return template(
                "post-incident-review-tracker",
                "Post-Incident Review & Action Item Tracker",
                "Conduct a structured post-incident review, identify root causes, generate action items, and track progress.",
                "Reliability",
                "Checklist",
                List.of("ai", "incident-management", "database", "approval", "slack"),
                g);
    }

    /* ------------------------------------------- data / document templates */

    private WorkflowTemplate multiSourceDataPipelineValidationReconciliation() {
        Graph g = graph();

        g.node("trigger", "schedule_trigger", "Data pipeline",
                Map.of("cron", "0 */6 * * *", "timezone", "UTC"),
                0, 1);

        g.node("extract", "http_batch", "Extract from sources",
                Map.of("items", List.of(
                                "https://api.vantis.run/source1/data",
                                "https://api.vantis.run/source2/data",
                                "https://api.vantis.run/source3/data"),
                        "method", "GET",
                        "headers", Map.of("Authorization", "Bearer {{trigger.secret}}")),
                1, 1);

        g.node("validate", "ai_agent", "Validate data quality",
                Map.of("instructions",
                                "Validate data quality and return:\n"
                                        + "- Valid records\n"
                                        + "- Invalid records\n"
                                        + "- Data quality score (0-100)",
                        "input", "{{extract.results}}"),
                2, 1);

        g.node("gate", "condition", "Quality acceptable?",
                Map.of("expression", "{{validate.output.quality_score}} >= 90"),
                3, 1);

        g.node("reconcile", "ai_agent", "Reconcile datasets",
                Map.of("instructions",
                                "Reconcile data from different sources "
                                        + "and identify discrepancies.",
                        "input", "{{validate.output.valid_records}}"),
                4, 0);

        g.node("load", "database_query", "Load to warehouse",
                Map.of("databaseType", "postgresql",
                        "host", "db.warehouse",
                        "database", "analytics",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "INSERT INTO consolidated_data (record_date, data, source) "
                                + "VALUES (NOW(), {{reconcile.output}}, 'multi_source')"),
                5, 0);

        g.node("alert", "notification", "Data quality alert",
                Map.of("channel", "slack",
                        "message", "Data quality issue detected: {{validate.output.quality_score}}"
                                + "% - Invalid records: {{validate.output.invalid_count}}"),
                4, 2);

        g.edge("trigger", "extract", null);
        g.edge("extract", "validate", null);
        g.edge("validate", "gate", null);
        g.edge("gate", "reconcile", "true");
        g.edge("gate", "alert", "false");
        g.edge("reconcile", "load", null);

        return template(
                "multi-source-data-pipeline-validation-reconciliation",
                "Multi-Source Data Pipeline with Validation & Reconciliation",
                "Extract, validate, reconcile, and load data from multiple sources.",
                "Data/Document Operations",
                "DataFlow",
                List.of("schedule", "http", "ai", "database", "notification"),
                g);
    }

    private WorkflowTemplate automatedReportGenerationDistribution() {
        Graph g = graph();

        g.node("trigger", "schedule_trigger", "Report generation",
                Map.of("cron", "0 0 * * MON", "timezone", "UTC"),
                0, 1);

        g.node("collect", "database_query", "Collect data",
                Map.of("databaseType", "postgresql",
                        "host", "db.analytics",
                        "database", "report_data",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "SELECT * FROM metrics WHERE date >= NOW() - INTERVAL '7 days'"),
                1, 1);

        g.node("generate", "ai_agent", "Generate report",
                Map.of("instructions",
                                "Generate a comprehensive report based on the data, "
                                        + "including insights, trends, and recommendations.",
                        "input", "{{collect.results}}"),
                2, 1);

        g.node("format", "ai_agent", "Format as PDF",
                Map.of("instructions",
                                "Convert the report content to a well-formatted PDF document "
                                        + "with charts, tables, and executive summary.",
                        "input", "{{generate.output}}"),
                3, 1);

        g.node("distribute", "email", "Distribute report",
                Map.of("to", "leadership@company.com",
                        "subject", "Weekly Report: {{trigger.timestamp}}",
                        "body", "Please find the weekly report attached.",
                        "attachment", "{{format.output}}"),
                4, 0);

        g.node("archive", "s3_upload", "Archive report",
                Map.of("bucket", "vantis-reports",
                        "key", "weekly/{{trigger.timestamp}}.pdf",
                        "content", "{{format.output}}"),
                4, 2);

        g.edge("trigger", "collect", null);
        g.edge("collect", "generate", null);
        g.edge("generate", "format", null);
        g.edge("format", "distribute", null);
        g.edge("format", "archive", null);

        return template(
                "automated-report-generation-distribution",
                "Automated Report Generation & Distribution",
                "Generate, format, distribute, and archive automated reports.",
                "Data/Document Operations",
                "ReportGen",
                List.of("schedule", "database", "ai", "email", "s3"),
                g);
    }

    private WorkflowTemplate documentProcessingEntityExtractionPipeline() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Document uploaded",
                Map.of("method", "POST"), 0, 1);

        g.node("extract", "information_extractor", "Extract entities",
                Map.of("fields", Map.of(
                                "parties", "Names of parties involved in the document",
                                "dates", "Important dates mentioned",
                                "amounts", "Monetary amounts specified",
                                "obligations", "Key obligations or responsibilities"),
                        "input", "{{trigger.body.content}}"),
                1, 1);

        g.node("validate", "ai_agent", "Validate extraction",
                Map.of("instructions",
                                "Validate the extracted entities for accuracy "
                                        + "and completeness. Return validated entities.",
                        "input", "{{extract.output}}"),
                2, 1);

        g.node("classify", "text_classifier", "Classify document type",
                Map.of("categories", List.of("contract", "invoice", "receipt", "letter", "report"),
                        "input", "{{trigger.body.content}}"),
                3, 1);

        g.node("store", "database_query", "Store extracted data",
                Map.of("databaseType", "postgresql",
                        "host", "db.documents",
                        "database", "document_entities",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "INSERT INTO document_entities "
                                + "(document_id, doc_type, parties, dates, amounts, obligations, extracted_at) "
                                + "VALUES ({{trigger.body.id}}, {{classify.output}}, {{validate.output.parties}}, "
                                + "{{validate.output.dates}}, {{validate.output.amounts}}, "
                                + "{{validate.output.obligations}}, NOW())"),
                4, 1);

        g.node("notify", "notification", "Processing complete",
                Map.of("channel", "slack",
                        "message", "Document {{trigger.body.id}} ({{classify.output}}) processed. "
                                + "Entities extracted: {{validate.output | json_dump}}"),
                5, 1);

        g.edge("trigger", "extract", null);
        g.edge("extract", "validate", null);
        g.edge("validate", "classify", null);
        g.edge("classify", "store", null);
        g.edge("store", "notify", null);

        return template(
                "document-processing-entity-extraction-pipeline",
                "Document Processing & Entity Extraction Pipeline",
                "Process documents, extract entities, classify types, and store structured data.",
                "Data/Document Operations",
                "DocExtract",
                List.of("webhook", "ai", "database", "notification"),
                g);
    }

    /* ------------------------------------------------ business templates */

    private WorkflowTemplate employeeOnboardingOffboardingOrchestration() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Employee event",
                Map.of("method", "POST"), 0, 1);

        g.node("route", "switch", "Route by event type",
                Map.of("field", "{{trigger.body.eventType}}",
                        "cases", List.of("onboarding", "offboarding")),
                1, 1);

        g.node("provision", "http_request", "Provision accounts",
                Map.of("method", "POST",
                        "url", "https://hr.vantis.run/accounts/provision",
                        "headers", Map.of("Authorization", "Bearer {{trigger.secret}}"),
                        "body", "{\"employeeId\": \"{{trigger.body.employeeId}}\", \"action\": \"provision\"}"),
                2, 0);

        g.node("cleanup", "http_request", "Cleanup access",
                Map.of("method", "POST",
                        "url", "https://hr.vantis.run/access/revoke",
                        "headers", Map.of("Authorization", "Bearer {{trigger.secret}}"),
                        "body", "{\"employeeId\": \"{{trigger.body.employeeId}}\", \"action\": \"revoke_all\"}"),
                2, 2);

        g.node("completeOnboarding", "database_query", "Complete onboarding",
                Map.of("databaseType", "postgresql",
                        "host", "db.hr",
                        "database", "employee_records",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "UPDATE employees SET status = 'ACTIVE', onboarded_at = NOW() "
                                + "WHERE id = {{trigger.body.employeeId}}"),
                3, 0);

        g.node("completeOffboarding", "database_query", "Complete offboarding",
                Map.of("databaseType", "postgresql",
                        "host", "db.hr",
                        "database", "employee_records",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "UPDATE employees SET status = 'INACTIVE', offboarded_at = NOW() "
                                + "WHERE id = {{trigger.body.employeeId}}"),
                3, 2);

        g.node("notifyTeam", "notification", "Notify team",
                Map.of("channel", "slack",
                        "message", "{{trigger.body.eventType}} completed for employee "
                                + "{{trigger.body.employeeId}}."),
                4, 1);

        g.edge("trigger", "route", null);
        g.edge("route", "provision", "onboarding");
        g.edge("route", "cleanup", "offboarding");
        g.edge("provision", "completeOnboarding", null);
        g.edge("cleanup", "completeOffboarding", null);
        g.edge("completeOnboarding", "notifyTeam", null);
        g.edge("completeOffboarding", "notifyTeam", null);

        return template(
                "employee-onboarding-offboarding-orchestration",
                "Employee Onboarding/Offboarding Orchestration",
                "Orchestrate employee lifecycle events with automated provisioning and cleanup.",
                "Business Operations",
                "EmployeeFlow",
                List.of("webhook", "http", "database", "notification", "switch"),
                g);
    }

    private WorkflowTemplate expenseApprovalPolicyValidation() {
        Graph g = graph();

        g.node("trigger", "webhook_trigger", "Expense submitted",
                Map.of("method", "POST"), 0, 1);

        g.node("validate", "ai_agent", "Validate policy compliance",
                Map.of("instructions",
                                "Validate the expense submission against company policy "
                                        + "and return: compliant/non-compliant, violations, suggested amount.",
                        "input", "{{trigger.body}}"),
                1, 1);

        g.node("route", "condition", "Policy compliant?",
                Map.of("expression", "{{validate.output.compliant}} == 'compliant'"),
                2, 1);

        g.node("approve", "notification", "Auto-approve",
                Map.of("channel", "slack",
                        "message", "Expense {{trigger.body.id}} auto-approved: "
                                + "{{validate.output.suggested_amount}} (within policy limits)"),
                3, 0);

        g.node("flag", "notification", "Flag for review",
                Map.of("channel", "slack",
                        "message", "Expense {{trigger.body.id}} flagged for review: "
                                + "{{validate.output.violations}}. "
                                + "Suggested amount: {{validate.output.suggested_amount}}"),
                3, 2);

        g.node("review", "human_approval", "Manager review",
                Map.of("prompt", "Approve this flagged expense despite the policy violations?"),
                4, 2);

        g.node("process", "database_query", "Process payment",
                Map.of("databaseType", "postgresql",
                        "host", "db.finance",
                        "database", "expense_payments",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "INSERT INTO expense_payments "
                                + "(expense_id, employee_id, amount, status, processed_at) "
                                + "VALUES ({{trigger.body.id}}, {{trigger.body.employeeId}}, "
                                + "{{validate.output.suggested_amount}}, 'APPROVED', NOW())"),
                5, 1);

        g.edge("trigger", "validate", null);
        g.edge("validate", "route", null);
        g.edge("route", "approve", "true");
        g.edge("route", "flag", "false");
        g.edge("approve", "process", null);
        g.edge("flag", "review", null);
        g.edge("review", "process", "approved");

        return template(
                "expense-approval-policy-validation",
                "Expense Approval with Policy Validation",
                "Validate expenses against policy, auto-approve compliant ones, route violations to a human.",
                "Business Operations",
                "ExpenseCheck",
                List.of("webhook", "ai", "database", "notification", "condition", "approval"),
                g);
    }

    private WorkflowTemplate vendorContractRenewalRiskAssessment() {
        Graph g = graph();

        g.node("trigger", "schedule_trigger", "Contract renewal check",
                Map.of("cron", "0 0 1 * *", "timezone", "UTC"),
                0, 1);

        g.node("fetch", "database_query", "Fetch expiring contracts",
                Map.of("databaseType", "postgresql",
                        "host", "db.legal",
                        "database", "vendor_contracts",
                        "username", "admin",
                        "password", "{{trigger.secret}}",
                        "sql", "SELECT * FROM vendor_contracts "
                                + "WHERE end_date BETWEEN NOW() AND NOW() + INTERVAL '90 days' "
                                + "AND auto_renew = true"),
                1, 1);

        g.node("assess", "ai_agent", "Assess renewal risk",
                Map.of("instructions",
                                "Assess the risk of renewing each contract "
                                        + "based on performance, compliance, and market conditions. "
                                        + "Return risk level (LOW/MEDIUM/HIGH) and recommendations.",
                        "input", "{{fetch.results}}"),
                2, 1);

        g.node("route", "switch", "Route by risk level",
                Map.of("field", "{{assess.output.riskLevel}}",
                        "cases", List.of("LOW", "MEDIUM", "HIGH")),
                3, 1);

        g.node("notifyLegal", "notification", "Notify legal team",
                Map.of("channel", "slack",
                        "message", "Low-risk contract renewals ready to proceed: "
                                + "{{assess.output | json_dump}}"),
                4, 0);

        g.node("initiateRenewal", "http_request", "Initiate renewal process",
                Map.of("method", "POST",
                        "url", "https://legal.vantis.run/contracts/renew",
                        "headers", Map.of("Authorization", "Bearer {{trigger.secret}}"),
                        "body", "{{fetch.results}}"),
                5, 0);

        g.node("notifyProcurement", "notification", "Notify procurement team",
                Map.of("channel", "slack",
                        "message", "Medium-risk contract renewals need procurement review: "
                                + "{{assess.output | json_dump}}"),
                4, 1);

        g.node("notifyExecutive", "notification", "Notify executive team",
                Map.of("channel", "slack",
                        "message", "High-risk contract renewals need executive review: "
                                + "{{assess.output | json_dump}}"),
                4, 2);

        g.edge("trigger", "fetch", null);
        g.edge("fetch", "assess", null);
        g.edge("assess", "route", null);
        g.edge("route", "notifyLegal", "LOW");
        g.edge("route", "notifyProcurement", "MEDIUM");
        g.edge("route", "notifyExecutive", "HIGH");
        g.edge("notifyLegal", "initiateRenewal", null);

        return template(
                "vendor-contract-renewal-risk-assessment",
                "Vendor Contract Renewal & Risk Assessment",
                "Assess renewal risks for expiring vendor contracts and route to appropriate teams.",
                "Business Operations",
                "ContractFlow",
                List.of("schedule", "database", "ai", "notification", "http", "switch"),
                g);
    }

    /* ------------------------------------------------------- template factory */

    private WorkflowTemplate template(
            String slug,
            String name,
            String description,
            String category,
            String icon,
            List<String> tags,
            Graph graph) {

        JsonNode document = graph.build();

        return new WorkflowTemplate(
                slug,
                name,
                description,
                category,
                icon,
                tags,
                extractProviders(document),
                document);
    }

    /* ------------------------------------------------ provider extraction */

    /**
     * Derives the visual provider identifiers from the actual workflow graph.
     *
     * <p>This deliberately does not look at the template title, description, or
     * tags. The graph is the source of truth.
     */
    private List<String> extractProviders(JsonNode graph) {
        LinkedHashSet<String> providers = new LinkedHashSet<>();

        JsonNode nodes = graph.path("nodes");

        if (!nodes.isArray()) {
            return List.of();
        }

        for (JsonNode node : nodes) {
            String type = node.path("type").asText("").strip();

            if (type.isBlank()) {
                continue;
            }

            JsonNode config = node.path("data").path("config");

            addProviderForNode(providers, type, config);
        }

        return List.copyOf(providers);
    }

    private void addProviderForNode(
            Set<String> providers,
            String nodeType,
            JsonNode config) {

        String normalized = nodeType.toLowerCase();

        switch (normalized) {

            case "webhook_trigger", "webhook", "outbound_webhook" ->
                    providers.add("webhook");

            case "http_request", "http", "http_batch", "rest_api", "rest-api" ->
                    providers.add("rest-api");

            case "human_approval" ->
                    providers.add("approval");

            case "email" ->
                    providers.add("email");

            case "ai_agent", "llm_chat", "ai_router", "ai_decision",
                    "text_classifier", "text_summarizer", "information_extractor" ->
                    addAiProvider(providers, config);

            case "notification" ->
                    addNotificationProvider(providers, config);

            case "database_query", "database_insert" ->
                    addDatabaseProvider(providers, config);

            // Pure control-flow / utility nodes have no external provider.
            case "manual_trigger", "schedule_trigger", "condition", "switch",
                    "transform", "delay", "stop_fail", "execution_monitor",
                    "anomaly_detector", "escalation" -> {
                // intentionally none
            }

            default ->
                    addProviderByNodeType(providers, normalized);
        }
    }

    /**
     * AI nodes can optionally expose a provider/model in their config.
     *
     * <p>Current built-in templates don't force a model, so the safe visual
     * identifier is {@code ai}. Once a saved agent/model specifies a provider,
     * that concrete provider is surfaced instead.
     */
    private void addAiProvider(Set<String> providers, JsonNode config) {

        String provider = firstNonBlank(
                config.path("provider").asText(""),
                config.path("providerType").asText(""));

        if (!provider.isBlank()) {
            providers.add(normalizeProvider(provider));
            return;
        }

        String model = config.path("model").asText("");

        if (!model.isBlank()) {
            String lower = model.toLowerCase();

            if (lower.contains("claude") || lower.contains("anthropic")) {
                providers.add("anthropic");
                return;
            }

            if (lower.contains("gpt")
                    || lower.contains("openai")
                    || lower.startsWith("o1")
                    || lower.startsWith("o3")
                    || lower.startsWith("o4")) {
                providers.add("openai");
                return;
            }
        }

        providers.add("ai");
    }

    private void addNotificationProvider(Set<String> providers, JsonNode config) {

        String channel = config.path("channel").asText("").strip().toLowerCase();

        if (channel.isBlank()) {
            providers.add("custom");
            return;
        }

        providers.add(normalizeProvider(channel));
    }

    private void addDatabaseProvider(Set<String> providers, JsonNode config) {

        String type = config.path("databaseType").asText("").strip().toLowerCase();

        providers.add(type.isBlank() ? "database" : normalizeProvider(type));
    }

    /**
     * Maps Vantis node types onto the same provider identifiers used by the
     * integration/provider catalog.
     */
    private void addProviderByNodeType(Set<String> providers, String nodeType) {

        if (nodeType.startsWith("github")) {
            providers.add("github");
            return;
        }
        if (nodeType.startsWith("gitlab")) {
            providers.add("gitlab");
            return;
        }
        if (nodeType.startsWith("vercel")) {
            providers.add("vercel");
            return;
        }
        if (nodeType.startsWith("sentry")) {
            providers.add("sentry");
            return;
        }
        if (nodeType.startsWith("pagerduty")) {
            providers.add("pagerduty");
            return;
        }
        if (nodeType.startsWith("linear")) {
            providers.add("linear");
            return;
        }
        if (nodeType.startsWith("jira")) {
            providers.add("jira");
            return;
        }
        if (nodeType.startsWith("asana")) {
            providers.add("asana");
            return;
        }
        if (nodeType.startsWith("hubspot")) {
            providers.add("hubspot");
            return;
        }
        if (nodeType.startsWith("salesforce")) {
            providers.add("salesforce");
            return;
        }
        if (nodeType.startsWith("pipedrive")) {
            providers.add("pipedrive");
            return;
        }
        if (nodeType.startsWith("slack")) {
            providers.add("slack");
            return;
        }
        if (nodeType.startsWith("discord")) {
            providers.add("discord");
            return;
        }
        if (nodeType.startsWith("teams") || nodeType.startsWith("microsoft_teams")) {
            providers.add("teams");
            return;
        }
        if (nodeType.startsWith("telegram")) {
            providers.add("telegram");
            return;
        }
        if (nodeType.startsWith("twilio")) {
            providers.add("twilio");
            return;
        }
        if (nodeType.startsWith("sendgrid")) {
            providers.add("sendgrid");
            return;
        }
        if (nodeType.startsWith("resend")) {
            providers.add("resend");
            return;
        }
        if (nodeType.startsWith("smtp")) {
            providers.add("smtp");
            return;
        }
        if (nodeType.startsWith("gmail")) {
            providers.add("gmail");
            return;
        }
        if (nodeType.startsWith("google_sheets")) {
            providers.add("google-sheets");
            return;
        }
        if (nodeType.startsWith("notion")) {
            providers.add("notion");
            return;
        }
        if (nodeType.startsWith("stripe")) {
            providers.add("stripe");
            return;
        }
        if (nodeType.startsWith("anthropic")) {
            providers.add("anthropic");
            return;
        }
        if (nodeType.startsWith("openai")) {
            providers.add("openai");
            return;
        }
        if (nodeType.startsWith("aws")) {
            providers.add("aws");
            return;
        }
        if (nodeType.startsWith("s3")) {
            providers.add("s3");
            return;
        }
        if (nodeType.startsWith("n8n")) {
            providers.add("n8n");
            return;
        }
        if (nodeType.startsWith("make")) {
            providers.add("make");
            return;
        }
        if (nodeType.startsWith("zapier")) {
            providers.add("zapier");
            return;
        }
        if (nodeType.startsWith("temporal")) {
            providers.add("temporal");
            return;
        }

        /*
         * Unknown provider-specific nodes are still represented rather than
         * silently disappearing. This allows the frontend to fall back to the
         * provider's generic icon metadata.
         */
        int underscore = nodeType.indexOf('_');

        if (underscore > 0) {
            providers.add(normalizeProvider(nodeType.substring(0, underscore)));
        }
    }

    private String normalizeProvider(String value) {

        String normalized = value.strip().toLowerCase();

        return switch (normalized) {

            case "google sheets", "googlesheets", "google_sheet", "google-sheets" ->
                    "google-sheets";

            case "rest", "rest_api", "rest-api", "http", "http_api", "http-api" ->
                    "rest-api";

            case "microsoft teams", "microsoft_teams", "microsoftteams" ->
                    "teams";

            case "google calendar", "google_calendar", "googlecalendar" ->
                    "google-calendar";

            case "ai_agent", "llm", "llm_chat" ->
                    "ai";

            case "postgres", "postgresql" ->
                    "postgresql";

            default ->
                    normalized;
        };
    }

    private String firstNonBlank(String first, String second) {

        if (first != null && !first.isBlank()) {
            return first.strip();
        }

        if (second != null && !second.isBlank()) {
            return second.strip();
        }

        return "";
    }

    /* -------------------------------------------------------- graph builder */

    private Graph graph() {
        return new Graph(mapper);
    }

    /**
     * Tiny builder for the stored graph shape ({@code data.label} +
     * {@code data.config}, positions on a column/row grid) so each template above
     * reads as its own pipeline rather than as JSON plumbing.
     */
    private static final class Graph {

        private static final int COLUMN_WIDTH = 280;
        private static final int ROW_HEIGHT = 140;

        private final ObjectMapper mapper;
        private final ArrayNode nodes;
        private final ArrayNode edges;

        private Graph(ObjectMapper mapper) {
            this.mapper = mapper;
            this.nodes = mapper.createArrayNode();
            this.edges = mapper.createArrayNode();
        }

        void node(
                String id,
                String type,
                String label,
                Map<String, Object> config,
                int column,
                int row) {

            ObjectNode node = mapper.createObjectNode();

            node.put("id", id);
            node.put("type", type);

            ObjectNode position = node.putObject("position");
            position.put("x", 80 + column * COLUMN_WIDTH);
            position.put("y", 80 + row * ROW_HEIGHT);

            ObjectNode data = node.putObject("data");
            data.put("label", label);
            data.set("config", mapper.valueToTree(config));

            nodes.add(node);
        }

        void edge(String source, String target, String sourceHandle) {

            ObjectNode edge = mapper.createObjectNode();

            edge.put("id",
                    "e-" + source + "-" + target
                            + (sourceHandle == null ? "" : "-" + sourceHandle));
            edge.put("source", source);
            edge.put("target", target);

            if (sourceHandle == null) {
                edge.putNull("sourceHandle");
            } else {
                edge.put("sourceHandle", sourceHandle);
            }

            edges.add(edge);
        }

        JsonNode build() {
            ObjectNode graph = mapper.createObjectNode();
            graph.set("nodes", nodes);
            graph.set("edges", edges);
            return graph;
        }
    }
}