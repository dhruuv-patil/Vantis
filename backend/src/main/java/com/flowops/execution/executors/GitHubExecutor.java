package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/** GitHub: creates an issue or sets a commit status via GitHub API. */
@Component
public class GitHubExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "github";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String repo = ctx.configString("repo");
        String operation = ctx.configString("operation");

        if (repo == null || repo.isBlank()) {
            return NodeResult.fail(
                    "GitHub requires 'repo' (owner/repo)."
            );
        }

        if (operation == null || operation.isBlank()) {
            return NodeResult.fail(
                    "GitHub requires 'operation' (createIssue or setCommitStatus)."
            );
        }

        String pat = ctx.secret("pat");

        if (pat == null || pat.isBlank()) {
            ObjectNode output = ctx.mapper().createObjectNode();
            output.put("success", false);
            output.put(
                    "reason",
                    "No connected GitHub integration — configure an integration with a personal access token."
            );

            ctx.log().warn("GitHub not executed: no PAT.");

            return NodeResult.success(output);
        }

        /*
         * The GitHub API base URL must come from the connected credential,
         * not from workflow/node configuration. This prevents a workflow
         * author from redirecting the GitHub token to an arbitrary server.
         */
        String baseUrl = ctx.secret("baseUrl");

        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.github.com";
        }

        baseUrl = baseUrl.trim().replaceAll("/+$", "");

        try {
            baseUrl = com.flowops.common.security.SsrfGuard
                    .validate(baseUrl)
                    .toString();

            java.net.http.HttpClient client =
                    java.net.http.HttpClient.newHttpClient();

            if ("createIssue".equalsIgnoreCase(operation)) {
                ObjectNode output = ctx.mapper().createObjectNode();

                String title = ctx.configString("title");
                String body = ctx.configString("body");

                if (title == null || title.isBlank()) {
                    return NodeResult.fail(
                            "GitHub createIssue requires 'title'."
                    );
                }

                String payload =
                        "{\"title\":\""
                                + ctx.interpolate(title).replace("\"", "\\\"")
                                + "\""
                                + (body != null
                                ? ",\"body\":\""
                                + ctx.interpolate(body).replace("\"", "\\\"")
                                + "\""
                                : "")
                                + "}";

                java.net.URI uri = java.net.URI.create(
                        baseUrl
                                + "/repos/"
                                + repo
                                + "/issues"
                );

                java.net.http.HttpRequest request =
                        java.net.http.HttpRequest.newBuilder()
                                .uri(uri)
                                .header(
                                        "Authorization",
                                        "Bearer " + pat
                                )
                                .header(
                                        "Accept",
                                        "application/vnd.github+json"
                                )
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .POST(
                                        java.net.http.HttpRequest.BodyPublishers
                                                .ofString(payload)
                                )
                                .build();

                java.net.http.HttpResponse<String> response =
                        client.send(
                                request,
                                java.net.http.HttpResponse.BodyHandlers
                                        .ofString()
                        );

                output.put(
                        "success",
                        response.statusCode() >= 200
                                && response.statusCode() < 300
                );

                output.put(
                        "status",
                        response.statusCode()
                );

                if (response.statusCode() >= 200
                        && response.statusCode() < 300) {

                    JsonNode resp =
                            ctx.mapper().readTree(response.body());

                    output.put(
                            "issueNumber",
                            resp.path("number").asInt(0)
                    );

                    output.put(
                            "issueUrl",
                            resp.path("html_url").asText("")
                    );
                }

                ctx.log().info(
                        "GitHub createIssue: "
                                + response.statusCode()
                );

                return NodeResult.success(output);

            } else if ("setCommitStatus".equalsIgnoreCase(operation)) {

                String sha = ctx.configString("commitSha");
                String state = ctx.configString("state");
                String context = ctx.configString("context");

                if (sha == null || sha.isBlank()) {
                    return NodeResult.fail(
                            "GitHub setCommitStatus requires 'commitSha'."
                    );
                }

                if (state == null || state.isBlank()) {
                    return NodeResult.fail(
                            "GitHub setCommitStatus requires 'state' (success/failure/pending/error)."
                    );
                }

                String payload =
                        "{\"state\":\""
                                + state
                                + "\""
                                + (context != null
                                ? ",\"context\":\""
                                + ctx.interpolate(context)
                                        .replace("\"", "\\\"")
                                + "\""
                                : "")
                                + "}";

                java.net.URI uri = java.net.URI.create(
                        baseUrl
                                + "/repos/"
                                + repo
                                + "/statuses/"
                                + sha
                );

                java.net.http.HttpRequest request =
                        java.net.http.HttpRequest.newBuilder()
                                .uri(uri)
                                .header(
                                        "Authorization",
                                        "Bearer " + pat
                                )
                                .header(
                                        "Accept",
                                        "application/vnd.github+json"
                                )
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .POST(
                                        java.net.http.HttpRequest.BodyPublishers
                                                .ofString(payload)
                                )
                                .build();

                java.net.http.HttpResponse<String> response =
                        client.send(
                                request,
                                java.net.http.HttpResponse.BodyHandlers
                                        .ofString()
                        );

                ObjectNode output =
                        ctx.mapper().createObjectNode();

                output.put(
                        "success",
                        response.statusCode() >= 200
                                && response.statusCode() < 300
                );

                output.put(
                        "status",
                        response.statusCode()
                );

                ctx.log().info(
                        "GitHub setCommitStatus: "
                                + response.statusCode()
                );

                return NodeResult.success(output);

            } else {
                return NodeResult.fail(
                        "GitHub unknown operation: " + operation
                );
            }

        } catch (Exception e) {
            return NodeResult.fail(
                    "GitHub error: " + e.getMessage()
            );
        }
    }
}