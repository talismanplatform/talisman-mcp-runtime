/*
 * Copyright 2026 Talisman Cloud Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.talismanplatform.mcp.runtime;

import java.io.InputStream;
import java.io.Reader;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.path.NodePath;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.StreamCache;
import org.apache.camel.WrappedFile;

import com.talismanplatform.mcp.runtime.McpServerDefinition.Prompt;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Resource;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Tool;

/**
 * Turns the out message of a capability's route into the MCP result (exchange contract in the README).
 */
final class McpResults {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private McpResults() {
    }

    /** {@code CallToolResult} with {@code isError: true} and one text item. */
    static ObjectNode toolError(String message) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").add(text(message == null ? "Error" : message));
        result.put("isError", true);
        return result;
    }

    /**
     * {@code Map}/{@code List}/POJO bodies become {@code structuredContent} and the same JSON as text. Text-like
     * bodies are parsed as JSON when the route sets {@code Content-Type: application/json} or the tool has an
     * {@code outputSchema}; otherwise they are one text item. With an {@code outputSchema}, a result that does not
     * match it is an error result.
     */
    static ObjectNode toolResult(Exchange exchange, Tool tool) {
        Message message = exchange.getMessage();
        Object body = message.getBody();
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        JsonNode structured = null;
        if (body == null) {
            if (tool.outputSchema() != null) {
                return toolError("The tool returned no result, but it has an outputSchema");
            }
            return result;
        }
        if (isTextLike(body)) {
            String text = exchange.getContext().getTypeConverter().convertTo(String.class, exchange, body);
            if (tool.outputSchema() != null || isJson(message.getHeader(Exchange.CONTENT_TYPE, String.class))) {
                try {
                    structured = MAPPER.readTree(text);
                } catch (JsonProcessingException e) {
                    if (tool.outputSchema() != null) {
                        return toolError("The tool result is not JSON: " + e.getOriginalMessage());
                    }
                }
            }
            if (structured == null) {
                content.add(text(text));
                return result;
            }
        } else if (isScalar(body)) {
            if (tool.outputSchema() == null) {
                content.add(text(String.valueOf(body)));
                return result;
            }
            structured = MAPPER.valueToTree(body);
        } else {
            structured = MAPPER.valueToTree(body);
        }
        if (tool.outputValidator() != null) {
            List<Error> errors = tool.outputValidator().validate(structured);
            if (!errors.isEmpty()) {
                return toolError("The tool result does not match its outputSchema: " + describe(errors));
            }
        }
        content.add(text(structured.toString()));
        result.set("structuredContent", structured);
        return result;
    }

    /**
     * {@code ReadResourceResult}: text for a textual {@code mimeType} (Map/List/POJO as JSON), otherwise a base64
     * {@code blob}; a String body is always text.
     */
    static ObjectNode resourceResult(Exchange exchange, Resource resource, String uri) {
        Object body = exchange.getMessage().getBody();
        ObjectNode item = MAPPER.createObjectNode();
        item.put("uri", uri);
        item.put("mimeType", resource.mimeType());
        if (body == null) {
            item.put("text", "");
        } else if (body instanceof CharSequence || isScalar(body)) {
            item.put("text", String.valueOf(body));
        } else if (!isTextLike(body)) {
            item.put("text", MAPPER.valueToTree(body).toString());
        } else if (isTextual(resource.mimeType())) {
            item.put("text", exchange.getContext().getTypeConverter().convertTo(String.class, exchange, body));
        } else {
            byte[] bytes = exchange.getContext().getTypeConverter().convertTo(byte[].class, exchange, body);
            item.put("blob", Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes));
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("contents").add(item);
        return result;
    }

    /**
     * {@code GetPromptResult}: a String body is one user message; a {@code List} holds the messages, each a String
     * (a user message) or a {@code {role, content}} map whose content is a String or a content block.
     *
     * @throws IllegalArgumentException when the body is neither
     */
    static ObjectNode promptResult(Exchange exchange, Prompt prompt) {
        Object body = exchange.getMessage().getBody();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("description", prompt.description());
        ArrayNode messages = result.putArray("messages");
        if (body instanceof List<?> list) {
            for (Object element : list) {
                messages.add(promptMessage(exchange, element));
            }
        } else if (body != null && isTextLike(body)) {
            messages.add(message("user",
                    text(exchange.getContext().getTypeConverter().convertTo(String.class, exchange, body))));
        } else {
            throw new IllegalArgumentException("The prompt route must return a String or a List of messages, not "
                    + (body == null ? "null" : body.getClass().getName()));
        }
        return result;
    }

    private static ObjectNode promptMessage(Exchange exchange, Object element) {
        if (element instanceof CharSequence text) {
            return message("user", text(text.toString()));
        }
        if (!(element instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("A prompt message must be a String or a {role, content} map, not "
                    + (element == null ? "null" : element.getClass().getName()));
        }
        Object role = map.get("role");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("A prompt message's role must be user or assistant, not " + role);
        }
        Object content = map.get("content");
        if (content instanceof CharSequence text) {
            return message((String) role, text(text.toString()));
        }
        if (content instanceof Map<?, ?>) {
            JsonNode block = MAPPER.valueToTree(content);
            if (block.path("type").isTextual()) {
                return message((String) role, block);
            }
        }
        throw new IllegalArgumentException("A prompt message's content must be a String or a content block with a type");
    }

    private static ObjectNode message(String role, JsonNode content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", role);
        message.set("content", content);
        return message;
    }

    static ObjectNode text(String text) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "text");
        item.put("text", text);
        return item;
    }

    static String describe(List<Error> errors) {
        return errors.stream().map(McpResults::describe).collect(Collectors.joining("; "));
    }

    static String describe(Error error) {
        return location("$", error) + ": " + error.getMessage();
    }

    /** The error's instance location as a JSON path below {@code base}, e.g. {@code $.tools.add.inputSchema.type}. */
    static String location(String base, Error error) {
        NodePath location = error.getInstanceLocation();
        StringBuilder path = new StringBuilder(base);
        for (int i = 0; location != null && i < location.getNameCount(); i++) {
            Object element = location.getElement(i);
            if (element instanceof Number) {
                path.append('[').append(element).append(']');
            } else {
                path.replace(0, path.length(), McpServerLoader.path(path.toString(), String.valueOf(element)));
            }
        }
        return path.toString();
    }

    /** Bodies Camel's type converter turns into text: String, bytes, streams, stream caches, files. */
    private static boolean isTextLike(Object body) {
        return body instanceof CharSequence || body instanceof byte[] || body instanceof InputStream
                || body instanceof Reader || body instanceof StreamCache || body instanceof WrappedFile;
    }

    private static boolean isScalar(Object body) {
        return body instanceof Number || body instanceof Boolean || body instanceof Character || body instanceof Enum;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = mediaType(contentType);
        return type.equals("application/json") || type.endsWith("+json");
    }

    static boolean isTextual(String mimeType) {
        String type = mediaType(mimeType);
        return type.startsWith("text/") || type.equals("application/json") || type.endsWith("+json")
                || type.equals("application/xml") || type.endsWith("+xml") || type.equals("application/yaml")
                || type.equals("application/x-yaml") || type.equals("application/javascript")
                || type.equals("application/sql") || type.equals("application/graphql");
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
