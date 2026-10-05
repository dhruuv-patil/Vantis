package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

/**
 * MongoDB: runs find/insert against MongoDB. Uses reflection to avoid
 * compile-time dependency.
 */
@Component
public class MongoDbExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "mongodb";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String uri = ctx.configString("uri");
        String database = ctx.configString("database");
        String collection = ctx.configString("collection");
        String operation = ctx.configString("operation");
        JsonNode filterNode = ctx.mapper().valueToTree(ctx.config().get("filter"));
        JsonNode documentNode = ctx.mapper().valueToTree(ctx.config().get("document"));
        String limitStr = ctx.configString("limit");

        if (uri == null || uri.isBlank())
            return NodeResult.fail("MongoDB requires 'uri'.");
        if (database == null || database.isBlank())
            return NodeResult.fail("MongoDB requires 'database'.");
        if (collection == null || collection.isBlank())
            return NodeResult.fail("MongoDB requires 'collection'.");
        if (operation == null || operation.isBlank())
            return NodeResult.fail("MongoDB requires 'operation' (find or insertOne).");

        int limit = 100;
        if (limitStr != null && !limitStr.isBlank()) {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            // Use reflection to load MongoDB driver classes
            Class<?> mongoClientClass = Class.forName("com.mongodb.client.MongoClients");
            Class<?> mongoClientInterface = Class.forName("com.mongodb.client.MongoClient");
            Class<?> mongoDatabaseClass = Class.forName("com.mongodb.client.MongoDatabase");
            Class<?> mongoCollectionClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> documentClass = Class.forName("org.bson.Document");

            String interpolatedUri = ctx.interpolate(uri);

            try {
                com.flowops.common.security.SsrfGuard.validateMongoUri(interpolatedUri);
            } catch (com.flowops.common.error.ApiException blocked) {
                return NodeResult.fail("MongoDB target rejected: " + blocked.getMessage());
            }

            Object client = mongoClientClass
                    .getMethod("create", String.class)
                    .invoke(null, interpolatedUri);
            Object db = mongoClientInterface.getMethod("getDatabase", String.class).invoke(client, database);
            Object coll = mongoDatabaseClass.getMethod("getCollection", String.class).invoke(db, collection);

            if ("find".equalsIgnoreCase(operation)) {
                Object query = documentClass.getConstructor().newInstance();
                if (filterNode != null && !filterNode.isNull()) {
                    query = documentClass.getMethod("parse", String.class).invoke(null,
                            ctx.interpolate(filterNode.toString()));
                }

                Object findIterable = mongoCollectionClass.getMethod("find", Object.class).invoke(coll, query);
                if (limit > 0) {
                    findIterable = findIterable.getClass().getMethod("limit", int.class).invoke(findIterable, limit);
                }

                ArrayNode rows = ctx.mapper().createArrayNode();
                for (Object doc : (Iterable<?>) findIterable) {
                    // Use Document#toJson() if available, otherwise convert via mapper
                    String json;
                    try {
                        json = (String) doc.getClass().getMethod("toJson").invoke(doc);
                    } catch (Exception e) {
                        json = ctx.mapper().writeValueAsString(doc);
                    }
                    JsonNode parsed = ctx.mapper().readTree(json);
                    rows.add(parsed);
                }

                ObjectNode output = ctx.mapper().createObjectNode();
                output.set("documents", rows);
                output.put("count", rows.size());
                ctx.log().info("MongoDB find returned " + rows.size() + " documents.");
                return NodeResult.success(output);

            } else if ("insertOne".equalsIgnoreCase(operation)) {
                Object doc = documentClass.getConstructor().newInstance();
                if (documentNode != null && !documentNode.isNull()) {
                    doc = documentClass.getMethod("parse", String.class).invoke(null,
                            ctx.interpolate(documentNode.toString()));
                }

                mongoCollectionClass.getMethod("insertOne", documentClass).invoke(coll, doc);

                ObjectNode output = ctx.mapper().createObjectNode();
                output.put("inserted", true);
                ctx.log().info("MongoDB insertOne succeeded.");
                return NodeResult.success(output);
            } else {
                return NodeResult.fail("MongoDB unknown operation: " + operation);
            }
        } catch (ClassNotFoundException e) {
            return NodeResult.fail("MongoDB driver not available: add mongodb-driver-sync to classpath.");
        } catch (Exception e) {
            return NodeResult.fail("MongoDB error: " + e.getMessage());
        }
    }
}