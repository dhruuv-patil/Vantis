package com.flowops.execution.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.execution.NodeExecutionContext;
import com.flowops.execution.NodeExecutor;
import com.flowops.execution.NodeResult;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;

/** XML Parse: parses an XML document into a JSON tree. */
@Component
public class XmlParseExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "xml_parse";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        String input = ctx.configString("input");
        if (input == null || input.isBlank()) {
            return NodeResult.fail("XML Parse requires an 'input' string.");
        }

        String interpolated = ctx.interpolate(input);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(interpolated)));
            JsonNode json = domToJson(ctx, doc.getDocumentElement());
            ctx.log().info("XML Parse succeeded.");
            return NodeResult.success(json);
        } catch (Exception e) {
            return NodeResult.fail("XML Parse failed: " + e.getMessage());
        }
    }

    private JsonNode domToJson(NodeExecutionContext ctx, Node node) {
        ObjectNode json = ctx.mapper().createObjectNode();
        if (node.getAttributes() != null) {
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                Node attr = node.getAttributes().item(i);
                json.put("@" + attr.getNodeName(), attr.getNodeValue());
            }
        }
        NodeList children = node.getChildNodes();
        boolean hasText = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String t = child.getNodeValue().trim();
                if (!t.isEmpty()) {
                    hasText = true;
                    text.append(t);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                JsonNode childJson = domToJson(ctx, child);
                String name = child.getNodeName();
                if (json.has(name)) {
                    JsonNode existing = json.get(name);
                    if (existing.isArray()) {
                        ((com.fasterxml.jackson.databind.node.ArrayNode) existing).add(childJson);
                    } else {
                        com.fasterxml.jackson.databind.node.ArrayNode arr = ctx.mapper().createArrayNode();
                        arr.add(existing);
                        arr.add(childJson);
                        json.set(name, arr);
                    }
                } else {
                    json.set(name, childJson);
                }
            }
        }
        if (hasText && json.size() == 0) {
            return ctx.mapper().getNodeFactory().textNode(text.toString());
        } else if (hasText) {
            json.put("#text", text.toString());
        }
        return json;
    }
}