package com.flowops.reliability;

import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.Incident;
import com.flowops.repository.IncidentRepository;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tracks external dependencies across workflows and correlates cascading failures.
 * Detects shared dependency incidents (e.g. "Likely shared dependency incident: Google Sheets API")
 * across multiple workflows to avoid alert storms and surface root causes.
 */
@Service
public class DependencyIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(DependencyIntelligenceService.class);
    private static final int CORRELATION_WORKFLOW_THRESHOLD = 2; // >= 2 distinct workflows failing on same dependency
    private static final long WINDOW_MINUTES = 15;

    public record FailureEvent(
            UUID workflowId,
            UUID executionId,
            String dependency,
            String error,
            Instant timestamp) {
    }

    // In-memory rolling window of recent dependency failures: orgId -> dependency -> list of FailureEvent
    private final ConcurrentHashMap<String, List<FailureEvent>> failureWindows = new ConcurrentHashMap<>();

    private final IncidentRepository incidentRepository;

    public DependencyIntelligenceService(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    /**
     * Resolves canonical external dependency name from node type and config.
     */
    public String resolveDependency(String nodeType, Map<String, Object> config) {
        if (nodeType == null) {
            return "Unknown";
        }
        String lower = nodeType.toLowerCase();

        if (lower.startsWith("stripe")) return "Stripe API";
        if (lower.startsWith("google_sheets")) return "Google Sheets API";
        if (lower.startsWith("slack")) return "Slack API";
        if (lower.startsWith("github")) return "GitHub API";
        if (lower.startsWith("jira")) return "Jira API";
        if (lower.startsWith("notion")) return "Notion API";
        if (lower.startsWith("linear")) return "Linear API";
        if (lower.startsWith("openai") || lower.startsWith("llm")) return "OpenAI API";
        if (lower.startsWith("database") || lower.startsWith("postgres") || lower.startsWith("mysql")) return "Database";
        if (lower.startsWith("s3")) return "AWS S3";
        if (lower.startsWith("pagerduty")) return "PagerDuty API";
        if (lower.startsWith("teams")) return "Microsoft Teams API";
        if (lower.startsWith("discord")) return "Discord API";

        if (config != null && ("http_request".equals(lower) || "outbound_webhook".equals(lower))) {
            Object urlObj = config.get("url");
            if (urlObj instanceof String urlStr && !urlStr.isBlank()) {
                try {
                    URI uri = URI.create(urlStr.trim());
                    String host = uri.getHost();
                    if (host != null && !host.isBlank()) {
                        return host;
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    /**
     * Records an execution node failure for a dependency and checks for multi-workflow correlation.
     */
    public void recordFailure(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            String dependency,
            String error) {

        if (organizationId == null || workflowId == null || dependency == null) {
            return;
        }

        String key = organizationId + ":" + dependency;
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW_MINUTES, ChronoUnit.MINUTES);

        failureWindows.compute(key, (k, existing) -> {
            List<FailureEvent> list = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            // Prune old events outside window
            list.removeIf(e -> e.timestamp().isBefore(cutoff));
            list.add(new FailureEvent(workflowId, executionId, dependency, error, now));
            return list;
        });

        checkCorrelation(organizationId, dependency);
    }

    public void recordDependencyFailure(
            UUID organizationId,
            String nodeType,
            String nodeId,
            String error) {
        String dep = resolveDependency(nodeType, null);
        if (dep != null) {
            recordFailure(organizationId, null, null, dep, error);
        }
    }

    private void checkCorrelation(UUID organizationId, String dependency) {
        String key = organizationId + ":" + dependency;
        List<FailureEvent> events = failureWindows.get(key);
        if (events == null || events.isEmpty()) {
            return;
        }

        Set<UUID> affectedWorkflows = new HashSet<>();
        for (FailureEvent e : events) {
            affectedWorkflows.add(e.workflowId());
        }

        if (affectedWorkflows.size() >= CORRELATION_WORKFLOW_THRESHOLD) {
            String title = "Likely shared dependency incident: " + dependency;
            String description = "Vantis detected " + events.size()
                    + " recent failures across " + affectedWorkflows.size()
                    + " workflows caused by " + dependency + ".";

            log.warn("Correlated shared dependency incident: '{}' for organization {}", title, organizationId);

            // Avoid duplicate open incidents for the same shared dependency
            var existingIncidents = incidentRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
            boolean alreadyOpen = existingIncidents.stream()
                    .anyMatch(inc -> title.equals(inc.getTitle()) && inc.getStatus() == com.flowops.domain.IncidentStatus.OPEN);

            if (!alreadyOpen) {
                // Attach first workflow for DB foreign key
                UUID firstWorkflowId = events.get(0).workflowId();
                Incident incident = Incident.open(
                        organizationId,
                        firstWorkflowId,
                        events.get(0).executionId(),
                        null,
                        title,
                        description,
                        AnomalySeverity.HIGH,
                        Map.of(
                                "dependency", dependency,
                                "affectedWorkflowsCount", affectedWorkflows.size(),
                                "affectedExecutionsCount", events.size(),
                                "kind", "shared-dependency-incident"));
                incidentRepository.save(incident);
            }
        }
    }
}
