package com.flowops.workflow.nodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for which node types exist and how they are shaped.
 *
 * <p>Kept deliberately data-driven: the builder palette, the config panel, and
 * the graph validator all read from the same definitions, so a node added here
 * shows up everywhere consistently. A type appearing here is a promise that an
 * executor registered in {@code com.flowops.execution.executors} will run it —
 * never fake output. Node types are arbitrary registry keys; the graph, the
 * {@code execution_nodes} rows, and the executor registry all key on the same
 * string, so adding nodes requires no schema change.
 *
 * <p>This catalogue covers 100 production node types across triggers, HTTP,
 * AI, logic/data transforms, databases & Redis, SaaS integrations, and
 * reliability/observability guards.
 */
@Component
public class NodeRegistry {

    private final Map<String, NodeDefinition> definitions = new LinkedHashMap<>();

    public NodeRegistry() {
        registerTriggers();
        registerHttp();
        registerAi();
        registerLogic();
        registerData();
        registerDatabases();
        registerIntegrations();
        registerReliability();
    }

    private void registerTriggers() {
        register(new NodeDefinition(
                "manual_trigger", "Manual Trigger",
                "Starts the workflow when a user clicks Run.",
                NodeCategory.TRIGGER, "MousePointerClick", true, 0, List.of("out"),
                List.of()));

        register(new NodeDefinition(
                "webhook_trigger", "Webhook Trigger",
                "Starts the workflow when an HTTP request hits its webhook URL.",
                NodeCategory.TRIGGER, "Webhook", true, 0, List.of("out"),
                List.of(
                        ConfigField.select("method", "HTTP Method", false,
                                List.of("POST", "GET", "PUT")))));

        register(new NodeDefinition(
                "schedule_trigger", "Schedule Trigger",
                "Starts the workflow on a cron schedule (e.g. every weekday 9am).",
                NodeCategory.TRIGGER, "CalendarClock", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("cron", "Cron expression", true,
                                "Five-field cron, e.g. \"0 9 * * 1-5\". Local server time."),
                        ConfigField.text("timezone", "Timezone (IANA)", false,
                                "Optional IANA timezone, e.g. Asia/Kolkata. Defaults to the server's."))));

        register(new NodeDefinition(
                "interval_trigger", "Interval Trigger",
                "Starts the workflow on a fixed time interval.",
                NodeCategory.TRIGGER, "Timer", true, 0, List.of("out"),
                List.of(
                        ConfigField.number("intervalSeconds", "Interval (seconds)", true,
                                "How often to fire, e.g. 3600 for hourly."))));

        register(new NodeDefinition(
                "workflow_trigger", "Workflow Trigger",
                "Starts the workflow when another workflow completes.",
                NodeCategory.TRIGGER, "Workflow", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("sourceWorkflow", "Source workflow id", false,
                                "Optional id of the workflow whose completion fires this trigger."))));

        register(new NodeDefinition(
                "event_trigger", "Event Trigger",
                "Starts the workflow when a matching platform event is published.",
                NodeCategory.TRIGGER, "Zap", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("eventName", "Event name", false,
                                "Optional event topic to listen for."),
                        ConfigField.json("match", "Match", false,
                                "Optional JSON to match against the event payload."))));

        register(new NodeDefinition(
                "email_trigger", "Email Trigger",
                "Starts the workflow when an email arrives in a monitored inbox.",
                NodeCategory.TRIGGER, "Mail", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("folder", "Folder", false, "IMAP folder, e.g. INBOX."),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "How often the inbox is polled (default 60)."))));

        register(new NodeDefinition(
                "file_trigger", "File Trigger",
                "Starts the workflow when a file appears or changes in a watched path.",
                NodeCategory.TRIGGER, "FolderInput", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("path", "Directory", false, "Absolute directory to watch."),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "How often the directory is scanned (default 60)."))));

        register(new NodeDefinition(
                "error_trigger", "Error Trigger",
                "Starts the workflow when an error/event of a given code is raised.",
                NodeCategory.TRIGGER, "CircleAlert", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("errorCode", "Error code", false,
                                "Optional code or topic the trigger reacts to."))));

        register(new NodeDefinition(
                "http_poll_trigger", "HTTP Poll Trigger",
                "Periodically polls an endpoint and starts the workflow with its response.",
                NodeCategory.TRIGGER, "Radar", true, 0, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The endpoint to poll."),
                        ConfigField.select("method", "HTTP Method", false,
                                List.of("GET", "POST")),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.multiline("body", "Body", false,
                                "Request body for POST polls."),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "How often to poll (default 60)."))));
    }

    private void registerHttp() {
        register(new NodeDefinition(
                "http_request", "HTTP Request",
                "Calls an external HTTP endpoint and captures the response.",
                NodeCategory.ACTION, "Globe", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("method", "Method", true,
                                List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                        ConfigField.text("url", "URL", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.multiline("body", "Body", false,
                                "Request body; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "graphql_request", "GraphQL Request",
                "Executes a GraphQL query or mutation against an endpoint.",
                NodeCategory.ACTION, "Network", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "GraphQL endpoint."),
                        ConfigField.multiline("query", "Query", true,
                                "The GraphQL query or mutation document."),
                        ConfigField.json("variables", "Variables", false,
                                "JSON object of query variables."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of extra header name/value pairs."))));

        register(new NodeDefinition(
                "api_health_check", "API Health Check",
                "Probes an endpoint and reports status, latency, and a healthy flag.",
                NodeCategory.ACTION, "HeartPulse", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The endpoint to probe."),
                        ConfigField.select("method", "Method", false,
                                List.of("GET", "HEAD", "POST")),
                        ConfigField.number("expectedStatus", "Expected status", false,
                                "Treat only this status as healthy (default 2xx)."),
                        ConfigField.number("timeoutSeconds", "Timeout (seconds)", false,
                                "Per-request timeout (default 10)."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."))));

        register(new NodeDefinition(
                "api_poll", "API Poll",
                "Polls an endpoint until a condition is met or a poll budget is exhausted.",
                NodeCategory.ACTION, "History", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The endpoint to poll."),
                        ConfigField.select("method", "Method", false,
                                List.of("GET", "POST")),
                        ConfigField.multiline("body", "Body", false,
                                "Request body for POST polls."),
                        ConfigField.text("stopWhen", "Stop when (JSONPath)", false,
                                "A dotted JSON path whose presence/truthiness stops polling, "
                                        + "e.g. status or data.job.complete."),
                        ConfigField.number("maxPolls", "Max polls", false,
                                "Stop after this many polls (default 10)."),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "Between polls (default 5)."))));

        register(new NodeDefinition(
                "webhook_response", "Webhook Response",
                "Shapes the response returned to an inbound webhook call.",
                NodeCategory.ACTION, "Send", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("status", "Status", false,
                                "HTTP status to respond with (default 200)."),
                        ConfigField.text("contentType", "Content-Type", false,
                                "Response content type (default application/json)."),
                        ConfigField.multiline("body", "Body", false,
                                "Response body; supports {{variable}} interpolation."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of extra response headers."))));

        register(new NodeDefinition(
                "download_file", "Download File",
                "Downloads a remote file to a local path (or inline) and reports the result.",
                NodeCategory.ACTION, "Download", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The file URL."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.text("targetPath", "Save to path", false,
                                "Absolute path to write the file to; optional — inline base64 when unset."),
                        ConfigField.number("maxBytes", "Max bytes", false,
                                "Cap on the downloaded body (default 1 MiB)."))));

        register(new NodeDefinition(
                "upload_file", "Upload File",
                "Uploads a local file (or inline content) to an endpoint as multipart form data.",
                NodeCategory.ACTION, "Upload", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The upload endpoint."),
                        ConfigField.select("method", "Method", false,
                                List.of("POST", "PUT")),
                        ConfigField.text("filePath", "File path", false,
                                "Absolute path to read the file from."),
                        ConfigField.multiline("inlineContent", "Inline content", false,
                                "Content to upload instead of a file path."),
                        ConfigField.text("fieldName", "Form field", false,
                                "Multipart field name (default \"file\")."),
                        ConfigField.text("fileName", "File name", false,
                                "Filename advertised in the part (defaults to the path basename)."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of extra header name/value pairs."))));

        register(new NodeDefinition(
                "http_batch", "HTTP Batch",
                "Runs a list of HTTP requests and returns their results in order.",
                NodeCategory.ACTION, "Layers", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("items", "Items", true,
                                "JSON array of URLs or of {url, method, body, headers} objects."),
                        ConfigField.select("method", "Default method", false,
                                List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                        ConfigField.json("headers", "Default headers", false,
                                "Applied to every request."),
                        ConfigField.number("concurrency", "Concurrency", false,
                                "Max parallel requests (default 4)."))));

        register(new NodeDefinition(
                "http_retry", "HTTP Retry",
                "Calls an endpoint with its own retry/backoff loop, returning the final result.",
                NodeCategory.ACTION, "RotateCw", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("method", "Method", false,
                                List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                        ConfigField.text("url", "URL", true, "The endpoint to call."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.multiline("body", "Body", false,
                                "Request body."),
                        ConfigField.number("maxAttempts", "Max attempts", false,
                                "Including the first (default 3)."),
                        ConfigField.number("backoffSeconds", "Backoff (seconds)", false,
                                "Base backoff between attempts, doubled each retry (default 1)."))));

        register(new NodeDefinition(
                "wait_for_http", "Wait for HTTP",
                "Waits until an endpoint returns a desired status (or the timeout elapses).",
                NodeCategory.ACTION, "Hourglass", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The endpoint to poll."),
                        ConfigField.select("method", "Method", false,
                                List.of("GET", "POST")),
                        ConfigField.number("desiredStatus", "Desired status", false,
                                "Consider the wait over at this status (default 2xx)."),
                        ConfigField.number("timeoutSeconds", "Timeout (seconds)", false,
                                "Give up after this long (default 300)."),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "Between polls (default 5)."))));

        register(new NodeDefinition(
                "rate_limited_request", "Rate Limited Request",
                "Makes a single request paced by a configurable requests-per-second budget.",
                NodeCategory.ACTION, "Gauge", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("method", "Method", true,
                                List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                        ConfigField.text("url", "URL", true, "The endpoint to call."),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.multiline("body", "Body", false,
                                "Request body."),
                        ConfigField.number("requestsPerSecond", "Requests/sec", true,
                                "Target throughput; the node paces itself to honor it."))));

        register(new NodeDefinition(
                "paginated_api_request", "Paginated API Request",
                "Walks an API's pages and concatenates the collected items.",
                NodeCategory.ACTION, "ListEnd", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("url", "URL", true, "The first page URL."),
                        ConfigField.select("method", "Method", false,
                                List.of("GET", "POST")),
                        ConfigField.json("headers", "Headers", false,
                                "JSON object of header name/value pairs."),
                        ConfigField.text("itemsJsonPath", "Items JSON path", false,
                                "Dotted path to the item array in each page (default: the body)."),
                        ConfigField.text("nextCursorPath", "Next page path", false,
                                "Dotted path resolving to the next page URL/ID token."),
                        ConfigField.text("cursorParam", "Cursor parameter", false,
                                "Query parameter name for the page token (e.g. after, cursor, page)."),
                        ConfigField.number("maxPages", "Max pages", false,
                                "Stop after this many pages (default 10)."))));
    }

    private void registerAi() {
        // ai_agent stays the flagship agent node (saved agents + tools).
        register(new NodeDefinition(
                "ai_agent", "AI Agent",
                "Runs an AI agent with instructions and returns its completion.",
                NodeCategory.AI, "Bot", false, 1, List.of("out"),
                List.of(
                        ConfigField.agent("agentId", "Saved agent", false,
                                "Optional: run one of your saved AI agents. Its instructions, "
                                        + "model, and tools take over when selected."),
                        ConfigField.multiline("instructions", "Instructions", false,
                                "System instructions for the agent. Used when no saved agent "
                                        + "is selected."),
                        ConfigField.multiline("input", "Input", false,
                                "User input; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "llm_chat", "LLM Chat",
                "A single-turn chat completion with system instructions and user input.",
                NodeCategory.AI, "MessageSquare", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("instructions", "Instructions", true,
                                "System instructions for the model."),
                        ConfigField.multiline("input", "Input", false,
                                "User message; supports {{variable}} interpolation."),
                        ConfigField.text("model", "Model", false,
                                "Optional model override (defaults to the AI provider's)."))));

        register(new NodeDefinition(
                "ai_router", "AI Router",
                "Asks the model to decide which of the named routes the input belongs to.",
                NodeCategory.AI, "Route", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("routes", "Routes", true,
                                "JSON array of route names, e.g. [\"sales\", \"support\", \"other\"]."),
                        ConfigField.multiline("input", "Input", false,
                                "The message/payload to route."))));

        register(new NodeDefinition(
                "ai_decision", "AI Decision",
                "Asks the model for a yes/no decision with a confidence and rationale.",
                NodeCategory.AI, "Scale", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("question", "Question", true,
                                "The decision asked of the model."),
                        ConfigField.multiline("input", "Context", false,
                                "Supporting context; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "text_classifier", "Text Classifier",
                "Classifies input text into one of the configured categories.",
                NodeCategory.AI, "Tags", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("categories", "Categories", true,
                                "JSON array of labels, e.g. [\"urgent\", \"normal\"]."),
                        ConfigField.multiline("input", "Text", false,
                                "The text to classify."))));

        register(new NodeDefinition(
                "text_summarizer", "Text Summarizer",
                "Produces a concise summary of the input text.",
                NodeCategory.AI, "AlignLeft", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("maxWords", "Max words", false,
                                "Optional target length."),
                        ConfigField.multiline("input", "Text", false,
                                "The text to summarize."))));

        register(new NodeDefinition(
                "sentiment_analyzer", "Sentiment Analyzer",
                "Returns the sentiment of the input text (positive/negative/neutral, score).",
                NodeCategory.AI, "Smile", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("input", "Text", false,
                                "The text to analyze."))));

        register(new NodeDefinition(
                "entity_extractor", "Entity Extractor",
                "Extracts named entities (people, orgs, places, dates, …) from text.",
                NodeCategory.AI, "User", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("entityTypes", "Entity types", false,
                                "JSON array of types to extract, e.g. [\"PERSON\", \"ORG\"]."),
                        ConfigField.multiline("input", "Text", false,
                                "The text to scan."))));

        register(new NodeDefinition(
                "information_extractor", "Information Extractor",
                "Extracts structured fields described by the config from a document.",
                NodeCategory.AI, "FileSearch", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("fields", "Fields", true,
                                "JSON mapping of field name → description to extract."),
                        ConfigField.multiline("input", "Source text", false,
                                "The document/text to extract from."))));

        register(new NodeDefinition(
                "json_generator", "JSON Generator",
                "Generates JSON that matches a described shape from the input.",
                NodeCategory.AI, "Braces", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("instructions", "Shape", true,
                                "Describe the JSON object(s) to generate."),
                        ConfigField.multiline("input", "Input", false,
                                "Source material; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "ai_data_validator", "AI Data Validator",
                "Validates data against stated rules and returns pass/fail with findings.",
                NodeCategory.AI, "ShieldCheck", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("rules", "Rules", true,
                                "The validation rules the data must satisfy."),
                        ConfigField.multiline("input", "Data", true,
                                "The data to validate; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "ai_content_generator", "AI Content Generator",
                "Generates content (copy, drafts, replies) from a brief and style guidance.",
                NodeCategory.AI, "PenSquare", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("brief", "Brief", true,
                                "What to generate, the audience, tone, length."),
                        ConfigField.multiline("input", "Source material", false,
                                "Optional source content; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "ai_researcher", "AI Researcher",
                "Answers a question using the supplied context, with a source-grounded answer.",
                NodeCategory.AI, "Search", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("question", "Question", true,
                                "The question to answer."),
                        ConfigField.multiline("input", "Context", false,
                                "Context to ground the answer in; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "ai_root_cause_analyzer", "AI Root Cause Analyzer",
                "Analyzes failure evidence and returns likely causes with confidence.",
                NodeCategory.AI, "Spline", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("instructions", "Instructions", false,
                                "Analysis instructions or prompt guidance."),
                        ConfigField.multiline("input", "Failure context", false,
                                "Logs, errors, and context; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "ai_anomaly_investigator", "AI Anomaly Investigator",
                "Investigates an anomaly from an evidence package and reports likely causes.",
                NodeCategory.AI, "Microscope", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("input", "Evidence", false,
                                "JSON evidence package; supports {{variable}} interpolation."))));
    }

    private void registerLogic() {
        register(new NodeDefinition(
                "if_else", "If/Else",
                "Evaluates one or more expressions and branches to true or false.",
                NodeCategory.LOGIC, "GitFork", false, 1, List.of("true", "false"),
                List.of(
                        ConfigField.json("conditions", "Conditions", false,
                                "JSON array of expressions; any true fires the true port."),
                        ConfigField.text("expression", "Expression", false,
                                "Single boolean expression, e.g. {{http.status}} == 200."))));

        register(new NodeDefinition(
                "condition", "Condition",
                "Evaluates an expression and branches to true or false.",
                NodeCategory.LOGIC, "GitFork", false, 1, List.of("true", "false"),
                List.of(
                        ConfigField.text("expression", "Expression", true,
                                "Boolean expression to evaluate; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "switch", "Switch",
                "Routes based on a value against up to four cases, else a default.",
                NodeCategory.LOGIC, "ToggleRight", false, 1,
                List.of("case1", "case2", "case3", "case4", "default"),
                List.of(
                        ConfigField.text("field", "Field", true,
                                "Dotted path to the value to match."),
                        ConfigField.json("cases", "Cases", true,
                                "JSON array of case values, mapped in order to case1…case4."))));

        register(new NodeDefinition(
                "router", "Router",
                "Routes based on regular expressions against up to six routes, else fallback.",
                NodeCategory.LOGIC, "Route", false, 1,
                List.of("route1", "route2", "route3", "route4", "route5", "route6", "default"),
                List.of(
                        ConfigField.text("input", "Input", true,
                                "The value to match (dotted path or literal)."),
                        ConfigField.json("routes", "Routes", true,
                                "JSON array of regex patterns, mapped in order to route1…route6."))));

        register(new NodeDefinition(
                "filter", "Filter",
                "Keeps or drops items of an array that satisfy a predicate.",
                NodeCategory.LOGIC, "ListFilter", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("array", "Array", true,
                                "Dotted path to the array in the variables."),
                        ConfigField.multiline("keepWhere", "Keep where", true,
                                "Predicate evaluated per item; reference the item as {{item.field}}."),
                        ConfigField.select("mode", "Mode", false,
                                List.of("keep", "drop")))));

        register(new NodeDefinition(
                "merge", "Merge",
                "Combines objects (shallow) or concatenates arrays from multiple sources.",
                NodeCategory.LOGIC, "Merge", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("sources", "Sources", true,
                                "JSON array of dotted paths to the values to merge."),
                        ConfigField.select("mode", "Mode", false,
                                List.of("object", "array")))));

        register(new NodeDefinition(
                "split", "Split",
                "Splits a string by a delimiter, or chunks an array into parts.",
                NodeCategory.LOGIC, "Scissors", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "Input", true,
                                "The string or array (dotted path or literal)."),
                        ConfigField.select("mode", "Mode", false,
                                List.of("delimiter", "chunks")),
                        ConfigField.text("delimiter", "Delimiter", false,
                                "Used in delimiter mode."),
                        ConfigField.number("chunkSize", "Chunk size", false,
                                "Used in chunks mode."))));

        register(new NodeDefinition(
                "for_each", "For Each",
                "Maps every item of an array through a template into an output array.",
                NodeCategory.LOGIC, "Repeat", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("array", "Array", true,
                                "Dotted path to the array to iterate."),
                        ConfigField.json("mapping", "Mapping", true,
                                "Per-item output shape; reference the item as {{item.field}}."),
                        ConfigField.number("limit", "Limit", false,
                                "Optional max number of items to process."))));

        register(new NodeDefinition(
                "loop", "Loop",
                "Builds an array by applying a mapping N times.",
                NodeCategory.LOGIC, "Loader", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("count", "Count", true,
                                "Number of iterations."),
                        ConfigField.json("mapping", "Mapping", true,
                                "Per-iteration output; reference the index as {{loop.index}}."))));

        register(new NodeDefinition(
                "while", "While",
                "Iterates a bounded mapping while a condition holds.",
                NodeCategory.LOGIC, "RefreshCcw", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("condition", "Condition", true,
                                "Evaluated per iteration; reference {{loop.index}}."),
                        ConfigField.json("mapping", "Mapping", true,
                                "Per-iteration output shape."),
                        ConfigField.number("maxIterations", "Max iterations", false,
                                "Hard cap against runaway loops (default 100)."))));

        register(new NodeDefinition(
                "wait", "Wait",
                "Pauses the workflow for an interpolated duration.",
                NodeCategory.LOGIC, "Hourglass", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("durationSeconds", "Duration (seconds)", true,
                                "Supports {{variable}} interpolation; capped by the server limit."))));

        // delay keeps the fixed-seconds node.
        register(new NodeDefinition(
                "delay", "Delay",
                "Pauses the workflow for a fixed duration.",
                NodeCategory.LOGIC, "Clock", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("seconds", "Delay (seconds)", true,
                                "How long to wait before continuing."))));

        register(new NodeDefinition(
                "conditional_wait", "Conditional Wait",
                "Pauses until an expression over the variables becomes true.",
                NodeCategory.LOGIC, "TimerReset", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("expression", "Expression", true,
                                "e.g. {{http.status}} == 200"),
                        ConfigField.number("pollIntervalSeconds", "Poll interval (seconds)", false,
                                "How often to re-check (default 5)."),
                        ConfigField.number("timeoutSeconds", "Timeout (seconds)", false,
                                "Give up after this long (default 300)."))));

        register(new NodeDefinition(
                "human_approval", "Human Approval",
                "Pauses until a human approves or rejects, then branches.",
                NodeCategory.LOGIC, "UserCheck", false, 1, List.of("approved", "rejected"),
                List.of(
                        ConfigField.multiline("prompt", "Approval prompt", true,
                                "Shown to the approver."))));

        register(new NodeDefinition(
                "try_catch", "Try/Catch",
                "Passes a payload through; a guard marker for protected sub-graphs.",
                NodeCategory.LOGIC, "BugOff", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("passThrough", "Pass-through path", false,
                                "Dotted path to forward as output (defaults to the trigger/upstream)."),
                        ConfigField.bool("logErrors", "Log errors", "Log a note about protected steps."))));

        register(new NodeDefinition(
                "stop_fail", "Stop/Fail",
                "Stops the run — either failing it or ending it early with a message.",
                NodeCategory.LOGIC, "OctagonX", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("mode", "Mode", true, List.of("fail", "stop")),
                        ConfigField.multiline("message", "Message", true,
                                "Shown in the run log; supports {{variable}} interpolation."))));

        register(new NodeDefinition(
                "retry", "Retry",
                "Declares a retry policy for downstream work and records it on each pass.",
                NodeCategory.LOGIC, "RotateCcw", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("maxAttempts", "Max attempts", false,
                                "Policy hint (the engine also applies its global retry)."),
                        ConfigField.number("backoffSeconds", "Backoff (seconds)", false,
                                "Policy hint."))));

        register(new NodeDefinition(
                "exponential_backoff", "Exponential Backoff",
                "Waits an exponentially-growing delay, then passes data through.",
                NodeCategory.LOGIC, "TrendingUp", false, 1, List.of("out"),
                List.of(
                        ConfigField.number("initialSeconds", "Initial (seconds)", true,
                                "First wait."),
                        ConfigField.number("factor", "Factor", false,
                                "Multiplier per step (default 2)."),
                        ConfigField.number("attempt", "Attempt", false,
                                "Which attempt this is; the wait = initial * factor^(attempt-1)."),
                        ConfigField.bool("sleep", "Sleep", "Wait before continuing instead of just reporting."))));

        register(new NodeDefinition(
                "timeout", "Timeout",
                "Fails the run when a measured upstream duration exceeds a budget.",
                NodeCategory.LOGIC, "Clock3", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("durationPath", "Duration path", true,
                                "Dotted path to a duration in milliseconds."),
                        ConfigField.number("timeoutMs", "Timeout (ms)", true,
                                "The budget; exceeding it fails the run."))));

        register(new NodeDefinition(
                "circuit_breaker", "Circuit Breaker",
                "Opens or closes based on a measured metric against a threshold.",
                NodeCategory.LOGIC, "CircuitBoard", false, 1, List.of("open", "closed"),
                List.of(
                        ConfigField.text("metricPath", "Metric path", true,
                                "Dotted path to a numeric metric in the variables."),
                        ConfigField.number("threshold", "Threshold", true,
                                "Metric above this trips the breaker open."))));

        register(new NodeDefinition(
                "rate_limiter", "Rate Limiter",
                "Assesses an observed rate against a budget and branches ok/exceeded.",
                NodeCategory.LOGIC, "TrafficCone", false, 1, List.of("ok", "exceeded"),
                List.of(
                        ConfigField.text("ratePath", "Rate path", true,
                                "Dotted path to the observed rate (events/interval)."),
                        ConfigField.number("maxRate", "Max rate", true,
                                "The budget; exceeding it takes the exceeded port."))));

        register(new NodeDefinition(
                "deduplicate", "Deduplicate",
                "Skips a duplicate payload by key; the first occurrence passes through.",
                NodeCategory.LOGIC, "Copy", false, 1, List.of("passed", "duplicate"),
                List.of(
                        ConfigField.text("key", "Key", true,
                                "The value to dedupe on (dotted path or literal)."))));

        register(new NodeDefinition(
                "idempotency_check", "Idempotency Check",
                "Reports whether a key was already processed and passes it either way.",
                NodeCategory.LOGIC, "Fingerprint", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("key", "Key", true,
                                "The value to look up (dotted path or literal)."))));

        register(new NodeDefinition(
                "error_handler", "Error Handler",
                "Sanitizes and annotates an upstream error message, then routes handled/risky.",
                NodeCategory.LOGIC, "TriangleAlert", false, 1, List.of("handled", "risky"),
                List.of(
                        ConfigField.text("errorPath", "Error path", true,
                                "Dotted path to the error message."),
                        ConfigField.number("maxLength", "Max length", false,
                                "Truncate the sanitized message to this many chars (default 200)."))));

        register(new NodeDefinition(
                "dead_letter_queue", "Dead Letter Queue",
                "Records a payload as undeliverable and returns the DLQ entry.",
                NodeCategory.LOGIC, "Archive", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("payloadPath", "Payload path", false,
                                "Dotted path to the failed payload (defaults to the upstream)."),
                        ConfigField.text("reason", "Reason", false,
                                "Why the payload is undeliverable."))));
    }

    private void registerData() {
        register(new NodeDefinition(
                "set_fields", "Set Fields",
                "Sets (adds/overrides) fields on the incoming object.",
                NodeCategory.ACTION, "SlidersHorizontal", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("values", "Values", true,
                                "JSON object mapping output keys to {{variable}} expressions."))));

        register(new NodeDefinition(
                "map_fields", "Map Fields",
                "Renames and reshapes fields via a key mapping.",
                NodeCategory.ACTION, "Map", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("mapping", "Mapping", true,
                                "JSON object mapping old key → new key."),
                        ConfigField.bool("keepUnmapped", "Keep unmapped",
                                "Keep fields not named in the mapping."))));

        register(new NodeDefinition(
                "rename_fields", "Rename Fields",
                "Renames selected keys, keeping everything else.",
                NodeCategory.ACTION, "Type", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("renames", "Renames", true,
                                "JSON object of old key → new key."))));

        register(new NodeDefinition(
                "remove_fields", "Remove Fields",
                "Removes selected keys from the incoming object.",
                NodeCategory.ACTION, "Eraser", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("fields", "Fields", true,
                                "JSON array of keys to remove."))));

        register(new NodeDefinition(
                "json_parse", "JSON Parse",
                "Parses a JSON string into a value.",
                NodeCategory.ACTION, "Braces", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "JSON string", true,
                                "The text to parse (dotted path or literal)."))));

        register(new NodeDefinition(
                "json_stringify", "JSON Stringify",
                "Serializes a value to a JSON string.",
                NodeCategory.ACTION, "Code2", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "Value", true,
                                "The value to serialize (dotted path or literal)."),
                        ConfigField.bool("pretty", "Pretty print", ""))));

        register(new NodeDefinition(
                "csv_parse", "CSV Parse",
                "Parses a delimited text table into rows of values.",
                NodeCategory.ACTION, "TableProperties", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "CSV text", true,
                                "The delimited text (dotted path or literal)."),
                        ConfigField.bool("hasHeader", "Has header row", ""),
                        ConfigField.text("delimiter", "Delimiter", false,
                                "Defaults to comma."))));

        register(new NodeDefinition(
                "csv_generate", "CSV Generate",
                "Serializes an array of objects into delimited text.",
                NodeCategory.ACTION, "FileSpreadsheet", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("rows", "Rows", true,
                                "Dotted path to the array of objects."),
                        ConfigField.json("columns", "Columns", false,
                                "Optional JSON array of key names in output order."),
                        ConfigField.text("delimiter", "Delimiter", false,
                                "Defaults to comma."))));

        register(new NodeDefinition(
                "xml_parse", "XML Parse",
                "Parses an XML document into a JSON tree.",
                NodeCategory.ACTION, "FileCode", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "XML text", true,
                                "The XML document (dotted path or literal)."))));

        register(new NodeDefinition(
                "regex_extract", "Regex Extract",
                "Extracts a capture group from text by pattern.",
                NodeCategory.ACTION, "Regex", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "Text", true,
                                "The text to search (dotted path or literal)."),
                        ConfigField.multiline("pattern", "Pattern", true,
                                "The regular expression; group 0 is the whole match."),
                        ConfigField.select("flags", "Flags", false,
                                List.of("NONE", "CASE_INSENSITIVE", "MULTILINE")),
                        ConfigField.number("group", "Capture group", false,
                                "Which group to return (default 0 = whole match)."))));

        register(new NodeDefinition(
                "text_formatter", "Text Formatter",
                "Applies a text operation: case, trim, replace, truncate, pad.",
                NodeCategory.ACTION, "AlignLeft", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "Text", true,
                                "The text (dotted path or literal)."),
                        ConfigField.select("operation", "Operation", true,
                                List.of("upper", "lower", "title", "trim", "replace", "truncate", "pad")),
                        ConfigField.text("find", "Find", false,
                                "For replace: the substring to replace."),
                        ConfigField.text("replaceWith", "Replace with", false,
                                "For replace: the replacement."),
                        ConfigField.number("length", "Length", false,
                                "For truncate/pad: the target length."),
                        ConfigField.text("padChar", "Pad char", false,
                                "For pad: the padding character (default space)."))));

        register(new NodeDefinition(
                "date_formatter", "Date Formatter",
                "Formats, adds, or subtracts time from a date.",
                NodeCategory.ACTION, "CalendarDays", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("input", "Date", false,
                                "ISO-8601 date or dotted path; empty uses now."),
                        ConfigField.text("fromFormat", "Input format", false,
                                "Optional custom input format (java.time pattern)."),
                        ConfigField.text("toFormat", "Output format", true,
                                "java.time output pattern, e.g. yyyy-MM-dd'T'HH:mm:ssXXX."),
                        ConfigField.select("operation", "Operation", false,
                                List.of("format", "addDays", "subtractDays")),
                        ConfigField.number("days", "Days", false,
                                "For add/subtract."))));

        register(new NodeDefinition(
                "transform", "Transform",
                "Builds an output object from a configurable field mapping.",
                NodeCategory.ACTION, "WandSparkles", false, 1, List.of("out"),
                List.of(
                        ConfigField.json("mapping", "Mapping", true,
                                "JSON object mapping output keys to values or {{variable}} expressions."))));

        register(new NodeDefinition(
                "template", "Template",
                "Renders a template with {{variable}} interpolation to text.",
                NodeCategory.ACTION, "SquareCode", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("template", "Template", true,
                                "Text with {{variable}} placeholders."),
                        ConfigField.bool("trimResult", "Trim result", ""))));
    }

    private void registerDatabases() {
        String connHelp = "JDBC connection fields. Leave password blank to rely on the server's "
                + "configured defaults; the driver must be on the backend classpath.";

        register(new NodeDefinition(
                "postgresql", "PostgreSQL",
                "Runs SQL against a PostgreSQL database via its JDBC driver.",
                NodeCategory.ACTION, "Database", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("host", "Host", true, "e.g. localhost."),
                        ConfigField.number("port", "Port", false, "Defaults to 5432."),
                        ConfigField.text("database", "Database", true, "Database name."),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The SQL statement to run."),
                        ConfigField.number("limit", "Max rows", false,
                                "Cap result rows for reads (default 100)."))));

        register(new NodeDefinition(
                "mysql", "MySQL",
                "Runs SQL against a MySQL/MariaDB database via its JDBC driver.",
                NodeCategory.ACTION, "DatabaseBackup", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("host", "Host", true, "e.g. localhost."),
                        ConfigField.number("port", "Port", false, "Defaults to 3306."),
                        ConfigField.text("database", "Database", true, "Database name."),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The SQL statement to run."),
                        ConfigField.number("limit", "Max rows", false,
                                "Cap result rows for reads (default 100)."))));

        register(new NodeDefinition(
                "mongodb", "MongoDB",
                "Runs a find/insert operation against a MongoDB database.",
                NodeCategory.ACTION, "Box", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("uri", "Connection URI", true,
                                "e.g. mongodb://localhost:27017"),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("collection", "Collection", true, ""),
                        ConfigField.select("operation", "Operation", true,
                                List.of("find", "insertOne")),
                        ConfigField.json("filter", "Filter", false,
                                "Query filter for find."),
                        ConfigField.json("document", "Document", false,
                                "Document for insertOne."),
                        ConfigField.number("limit", "Limit", false,
                                "For find (default 100)."))));

        register(new NodeDefinition(
                "redis_get", "Redis Get",
                "Reads a value by key from a Redis server.",
                NodeCategory.ACTION, "KeyRound", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("host", "Host", true, "e.g. localhost."),
                        ConfigField.number("port", "Port", false, "Defaults to 6379."),
                        ConfigField.number("db", "DB index", false, "Optional database index."),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.text("key", "Key", true, "The key to read."))));

        register(new NodeDefinition(
                "redis_set", "Redis Set",
                "Writes a value by key to a Redis server.",
                NodeCategory.ACTION, "KeySquare", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("host", "Host", true, "e.g. localhost."),
                        ConfigField.number("port", "Port", false, "Defaults to 6379."),
                        ConfigField.number("db", "DB index", false, "Optional database index."),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.text("key", "Key", true, "The key to write."),
                        ConfigField.multiline("value", "Value", true, "The value to store."),
                        ConfigField.number("ttlSeconds", "TTL (seconds)", false,
                                "Expiry; unset for no expiry."),
                        ConfigField.select("mode", "Mode", false,
                                List.of("SET", "SETEX", "SETNX")))));

        register(new NodeDefinition(
                "database_query", "Database Query",
                "Runs a read (SELECT) against a JDBC database and returns the rows.",
                NodeCategory.ACTION, "DatabaseZap", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("databaseType", "Database", true,
                                List.of("postgresql", "mysql")),
                        ConfigField.text("host", "Host", true, ""),
                        ConfigField.number("port", "Port", false, ""),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The SELECT to run."),
                        ConfigField.number("limit", "Max rows", false, "Default 100."))));

        register(new NodeDefinition(
                "database_insert", "Database Insert",
                "Runs an INSERT against a JDBC database and returns the affected count.",
                NodeCategory.ACTION, "DatabasePlus", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("databaseType", "Database", true,
                                List.of("postgresql", "mysql")),
                        ConfigField.text("host", "Host", true, ""),
                        ConfigField.number("port", "Port", false, ""),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The INSERT to run."))));

        register(new NodeDefinition(
                "database_update", "Database Update",
                "Runs an UPDATE against a JDBC database and returns the affected count.",
                NodeCategory.ACTION, "Database", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("databaseType", "Database", true,
                                List.of("postgresql", "mysql")),
                        ConfigField.text("host", "Host", true, ""),
                        ConfigField.number("port", "Port", false, ""),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The UPDATE to run."))));

        register(new NodeDefinition(
                "database_delete", "Database Delete",
                "Runs a DELETE against a JDBC database and returns the affected count.",
                NodeCategory.ACTION, "DatabaseMinus", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("databaseType", "Database", true,
                                List.of("postgresql", "mysql")),
                        ConfigField.text("host", "Host", true, ""),
                        ConfigField.number("port", "Port", false, ""),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.multiline("sql", "SQL", true, "The DELETE to run."))));

        register(new NodeDefinition(
                "database_transaction", "Database Transaction",
                "Runs a list of statements in one transaction with commit or rollback.",
                NodeCategory.ACTION, "Layers2", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("databaseType", "Database", true,
                                List.of("postgresql", "mysql")),
                        ConfigField.text("host", "Host", true, ""),
                        ConfigField.number("port", "Port", false, ""),
                        ConfigField.text("database", "Database", true, ""),
                        ConfigField.text("username", "User", true, ""),
                        ConfigField.text("password", "Password", false, ""),
                        ConfigField.json("statements", "Statements", true,
                                "JSON array of SQL statements."))));
    }

    private void registerIntegrations() {
        String integrationHelp = "Optional id of a connected integration. Leave blank to use the "
                + "organization's matching connection.";

        register(new NodeDefinition(
                "telegram", "Telegram",
                "Sends a message through the connected Telegram BOT integration.",
                NodeCategory.ACTION, "MessageCircle", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("chatId", "Chat ID", false,
                                "Optional override for default chat id."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "twilio", "Twilio",
                "Sends an SMS through the connected Twilio integration.",
                NodeCategory.ACTION, "Smartphone", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("to", "To", true, "Destination phone number in E.164 format."),
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "stripe", "Stripe",
                "Retrieves customer or transaction info via connected Stripe integration.",
                NodeCategory.ACTION, "CreditCard", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("operation", "Operation", true,
                                List.of("retrieveCustomer", "listBalanceTransactions")),
                        ConfigField.text("customerId", "Customer ID", false, ""),
                        ConfigField.text("limit", "Limit", false, "Result limit (default 10)."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "openai", "OpenAI",
                "Runs chat completions via connected OpenAI integration.",
                NodeCategory.AI, "Bot", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("prompt", "Prompt", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.multiline("systemPrompt", "System Instructions", false, ""),
                        ConfigField.text("model", "Model", false, "Defaults to gpt-4o-mini."),
                        ConfigField.text("maxTokens", "Max Tokens", false, "Default 500."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "hubspot", "HubSpot",
                "Creates contacts or deals via connected HubSpot integration.",
                NodeCategory.ACTION, "Briefcase", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("operation", "Operation", true,
                                List.of("createContact", "createDeal")),
                        ConfigField.text("email", "Email", false, "For createContact."),
                        ConfigField.text("firstName", "First Name", false, ""),
                        ConfigField.text("lastName", "Last Name", false, ""),
                        ConfigField.text("company", "Company", false, ""),
                        ConfigField.text("dealName", "Deal Name", false, "For createDeal."),
                        ConfigField.text("amount", "Amount", false, ""),
                        ConfigField.text("stage", "Stage", false, ""),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "slack", "Slack",
                "Posts a message to Slack through the connected Slack integration.",
                NodeCategory.ACTION, "Hash", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "notification", "Notification",
                "Sends a notification through a connected notification provider.",
                NodeCategory.ACTION, "Bell", false, 1, List.of("out"),
                List.of(
                        ConfigField.select("channel", "Channel", true,
                                List.of("slack", "email", "discord", "teams", "webhook")),
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("subject", "Subject", false,
                                "Optional notification subject."),
                        ConfigField.text("to", "To", false,
                                "Optional notification recipient."),
                        ConfigField.text("integrationId", "Integration id", false,
                                integrationHelp))));

        register(new NodeDefinition(
                "discord", "Discord",
                "Posts a message to Discord through the connected Discord integration.",
                NodeCategory.ACTION, "MessageCircle", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.multiline("username", "Username", false,
                                "Optional display name."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "teams", "Microsoft Teams",
                "Posts a message to a Teams channel through the connected Teams integration.",
                NodeCategory.ACTION, "Users", false, 1, List.of("out"),
                List.of(
                        ConfigField.multiline("message", "Message", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("title", "Title", false, "Optional message title."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "email", "Email",
                "Sends an email through the connected SMTP integration.",
                NodeCategory.ACTION, "MailOpen", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("to", "To", true, "Comma-separated recipients."),
                        ConfigField.text("subject", "Subject", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.multiline("body", "Body", false,
                                "Supports {{variable}} interpolation."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "gmail", "Gmail",
                "Sends email through Gmail's SMTP relays via a connected email integration.",
                NodeCategory.ACTION, "Mail", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("to", "To", true, "Comma-separated recipients."),
                        ConfigField.text("subject", "Subject", true, ""),
                        ConfigField.multiline("body", "Body", false, ""),
                        ConfigField.text("integrationId", "Integration id", false,
                                "An email (SMTP) integration with Gmail credentials."))));

        register(new NodeDefinition(
                "google_sheets_append_row", "Google Sheets Append Row",
                "Appends a row to a Google Sheet.",
                NodeCategory.ACTION, "Sheet", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("spreadsheetId", "Spreadsheet ID", true, "The id from the sheet's URL."),
                        ConfigField.json("row", "Row Data", true, "JSON array of cell values."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "linear_create_issue", "Linear Create Issue",
                "Creates an issue in Linear through the connected integration.",
                NodeCategory.ACTION, "Ticket", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("title", "Title", true, "Issue title."),
                        ConfigField.text("teamId", "Team ID", true, "Linear team ID."),
                        ConfigField.multiline("description", "Description", false, ""),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "github_create_issue", "GitHub Create Issue",
                "Creates an issue in GitHub through the connected integration.",
                NodeCategory.ACTION, "GitPullRequest", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("repo", "Repository", true, "owner/repo."),
                        ConfigField.text("title", "Title", true, "Issue title."),
                        ConfigField.multiline("body", "Body", false, "Description."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));


        register(new NodeDefinition(
                "salesforce_create_lead", "Salesforce Create Lead",
                "Creates a lead in Salesforce.",
                NodeCategory.ACTION, "UserPlus", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("lastName", "Last Name", true, "Lead last name."),
                        ConfigField.text("company", "Company", true, "Company name."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));


        register(new NodeDefinition(
                "pagerduty_trigger_incident", "PagerDuty Trigger Incident",
                "Triggers an incident in PagerDuty.",
                NodeCategory.ACTION, "Alert", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("serviceId", "Service ID", true, ""),
                        ConfigField.text("title", "Title", true, ""),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));


        register(new NodeDefinition(
                "jira", "Jira",
                "Creates a Jira issue via the Jira Cloud REST API.",
                NodeCategory.ACTION, "Ticket", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("siteUrl", "Site URL", true,
                                "e.g. https://yourorg.atlassian.net"),
                        ConfigField.text("project", "Project key", true, ""),
                        ConfigField.select("issueType", "Issue type", false,
                                List.of("Task", "Bug", "Story")),
                        ConfigField.text("summary", "Summary", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.multiline("description", "Description", false, ""),
                        ConfigField.text("integrationId", "Integration id", false,
                                "A Jira integration with email + API token."))));

        register(new NodeDefinition(
                "notion_create_page", "Notion Create Page",
                "Creates a page in a Notion database.",
                NodeCategory.ACTION, "NotebookPen", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("databaseId", "Database ID", true, "Notion database ID."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

        register(new NodeDefinition(
                "s3_upload", "S3 Upload",
                "Uploads content to an AWS S3 bucket.",
                NodeCategory.ACTION, "Upload", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("bucket", "Bucket Name", true, "AWS S3 bucket name."),
                        ConfigField.text("key", "Key", true, "S3 object key."),
                        ConfigField.multiline("content", "Content", true, "Content to upload."),
                        ConfigField.text("integrationId", "Integration id", false, integrationHelp))));

    }

    private void registerReliability() {
        register(new NodeDefinition(
                "execution_monitor", "Execution Monitor",
                "Reads an execution status/summary from the variables and reports it.",
                NodeCategory.ACTION, "MonitorEye", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("statusPath", "Status path", false,
                                "Dotted path to an execution status string."),
                        ConfigField.text("durationPath", "Duration path", false,
                                "Dotted path to a duration in ms."))));

        register(new NodeDefinition(
                "health_check", "Health Check",
                "Evaluates named checks against values and branches healthy/unhealthy.",
                NodeCategory.ACTION, "Stethoscope", false, 1, List.of("healthy", "unhealthy"),
                List.of(
                        ConfigField.json("checks", "Checks", true,
                                "JSON array of {path, op (==|!=|>|<|contains), value} objects."))));

        register(new NodeDefinition(
                "sla_monitor", "SLA Monitor",
                "Assesses success rate and latency against an SLA and branches inSla/breached.",
                NodeCategory.ACTION, "LineChart", false, 1, List.of("inSla", "breached"),
                List.of(
                        ConfigField.text("successRatePath", "Success rate path", true,
                                "Dotted path to a 0–1 success rate."),
                        ConfigField.text("latencyPath", "Latency path", true,
                                "Dotted path to a latency in ms."),
                        ConfigField.number("minSuccessRate", "Min success rate", true,
                                "0–1 floor."),
                        ConfigField.number("maxLatencyMs", "Max latency (ms)", true, ""))));

        register(new NodeDefinition(
                "anomaly_detector", "Anomaly Detector",
                "Flags outliers in a numeric series using a median-absolute-deviation gate.",
                NodeCategory.ACTION, "ScanSearch", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("seriesPath", "Series path", true,
                                "Dotted path to an array of numbers."),
                        ConfigField.number("zThreshold", "Z threshold", false,
                                "MAD z-score above which a point is anomalous (default 2.5)."))));

        register(new NodeDefinition(
                "incident_creator", "Incident Creator",
                "Creates an incident record and notifies the configured channel.",
                NodeCategory.ACTION, "Siren", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("title", "Title", true,
                                "Supports {{variable}} interpolation."),
                        ConfigField.select("severity", "Severity", false,
                                List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")),
                        ConfigField.multiline("body", "Body", false, ""),
                        ConfigField.select("channel", "Notify channel", false,
                                List.of("slack", "email", "discord", "teams", "webhook")),
                        ConfigField.text("integrationId", "Integration id", false,
                                "Optional integration for the notification."))));

        register(new NodeDefinition(
                "escalation", "Escalation",
                "Escalates the run to a channel with stepped urgency.",
                NodeCategory.ACTION, "TrendingUp", false, 1, List.of("out"),
                List.of(
                        ConfigField.text("title", "Title", true, ""),
                        ConfigField.multiline("body", "Body", false, ""),
                        ConfigField.number("level", "Level", false,
                                "Escalation step (1, 2, …) appended to the notification."),
                        ConfigField.select("channel", "Channel", false,
                                List.of("slack", "email", "discord", "teams", "webhook")),
                        ConfigField.text("integrationId", "Integration id", false, ""))));
    }

    private void register(NodeDefinition definition) {
        definitions.put(definition.type(), definition);
    }

    /** All definitions in a stable, palette-friendly order. */
    public List<NodeDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public Optional<NodeDefinition> find(String type) {
        return Optional.ofNullable(definitions.get(type));
    }

    public boolean isKnown(String type) {
        return definitions.containsKey(type);
    }
}