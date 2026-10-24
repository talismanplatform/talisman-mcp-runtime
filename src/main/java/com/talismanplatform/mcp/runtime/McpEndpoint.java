/*
 * Copyright 2026 Talisman Platform
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

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.util.CaseInsensitiveMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.talismanplatform.mcp.runtime.McpAuthenticator.Caller;
import com.talismanplatform.mcp.runtime.McpAuthenticator.Unauthorized;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Capability;
import com.talismanplatform.mcp.runtime.McpServerDefinition.ParamHeader;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Prompt;
import com.talismanplatform.mcp.runtime.McpServerDefinition.PromptArgument;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Resource;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Tool;

/**
 * {@code POST /mcp}: MCP 2026-07-28 over stateless Streamable HTTP, one JSON-RPC request per POST, answered with one
 * JSON object.
 */
final class McpEndpoint implements Processor {

    static final String PROTOCOL_VERSION = "2026-07-28";
    static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private static final Logger LOG = LoggerFactory.getLogger(McpEndpoint.class);
    private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]*={0,2}");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /** An HTTP response: status, extra headers, and a JSON body or none. */
    record Response(int status, Map<String, String> headers, JsonNode body) {
        static Response of(int status, JsonNode body) {
            return new Response(status, Map.of(), body);
        }
    }

    /** A request that ended early with this response (auth, scopes). */
    private static final class Rejected extends RuntimeException {
        private final transient Response response;

        Rejected(Response response) {
            super(null, null, false, false);
            this.response = response;
        }
    }

    private final McpServer server;

    McpEndpoint(McpServer server) {
        this.server = server;
    }

    @Override
    public void process(Exchange exchange) {
        Message message = exchange.getMessage();
        Map<String, Object> headers = requestHeaders(message);
        String method = message.getHeader(Exchange.HTTP_METHOD, String.class);
        String body = "POST".equals(method) ? message.getBody(String.class) : null;
        Response response = handle(method, headers, body);
        write(message, response);
    }

    /** The request headers; Camel's HTTP header filter drops {@code Host}, so it comes from the request URL. */
    static Map<String, Object> requestHeaders(Message message) {
        Map<String, Object> headers = new CaseInsensitiveMap(message.getHeaders());
        String url = message.getHeader(Exchange.HTTP_URL, String.class);
        if (header(headers, "Host") == null && url != null) {
            try {
                String authority = new URI(url).getRawAuthority();
                if (authority != null) {
                    headers.put("Host", authority);
                }
            } catch (Exception e) {
                // no Host: the base URL falls back to localhost
            }
        }
        return headers;
    }

    /** Request headers never go back to the client: the response carries only its own. */
    static void write(Message message, Response response) {
        message.removeHeaders("*");
        message.setHeader(Exchange.HTTP_RESPONSE_CODE, response.status());
        response.headers().forEach(message::setHeader);
        if (response.body() == null) {
            message.setBody(null);
        } else {
            message.setHeader(Exchange.CONTENT_TYPE, "application/json");
            message.setBody(response.body().toString());
        }
    }

    Response handle(String httpMethod, Map<String, Object> headers, String body) {
        if (!"POST".equals(httpMethod)) {
            return new Response(405, Map.of("Allow", "POST"), null);
        }
        if (!originAllowed(headers)) {
            return Response.of(403, error(null, McpError.INVALID_REQUEST, "Origin not allowed", null));
        }
        McpServer.State state = server.state();
        if (state == null) {
            return Response.of(503, error(null, McpError.INTERNAL_ERROR, "The MCP server is starting", null));
        }
        JsonNode request;
        try {
            request = body == null || body.isBlank() ? null : McpResults.MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return Response.of(400, error(null, McpError.PARSE_ERROR, "Parse error: " + e.getOriginalMessage(), null));
        }
        if (request == null || !request.isObject()) {
            return Response.of(400, error(null, McpError.INVALID_REQUEST,
                    "The body must be one JSON-RPC request or notification", null));
        }
        JsonNode id = request.get("id");
        if (!"2.0".equals(request.path("jsonrpc").asText(null)) || !request.path("method").isTextual()
                || (id != null && !id.isTextual() && !id.isIntegralNumber())) {
            return Response.of(400, error(id != null && (id.isTextual() || id.isNumber()) ? id : null,
                    McpError.INVALID_REQUEST, "Invalid JSON-RPC request", null));
        }
        String method = request.get("method").asText();
        JsonNode params = request.path("params");
        try {
            Caller caller = authenticate(state, headers);
            if (id == null) {
                return new Response(202, Map.of(), null); // notifications: accepted, nothing to do in v1
            }
            if (method.equals("initialize")) {
                // removed in 2026-07-28; the data tells a legacy client which version to speak
                ObjectNode data = McpResults.MAPPER.createObjectNode();
                data.putArray("supported").add(PROTOCOL_VERSION);
                throw new McpError(McpError.METHOD_NOT_FOUND, "Method not found: initialize (this server speaks "
                        + PROTOCOL_VERSION + ", stateless)", 404, data);
            }
            checkProtocolHeaders(method, params, headers);
            ObjectNode result = dispatch(state, caller, method, params, headers);
            result.put("resultType", "complete");
            ObjectNode serverInfo = result.putObject("_meta").putObject(META_SERVER_INFO);
            serverInfo.put("name", state.definition().info().name());
            serverInfo.put("version", state.definition().info().version());
            ObjectNode response = McpResults.MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return Response.of(200, response);
        } catch (Rejected e) {
            return e.response;
        } catch (McpError e) {
            return Response.of(e.getHttpStatus(), error(id, e.getCode(), e.getMessage(), e.getData()));
        } catch (RuntimeException e) {
            LOG.warn("MCP {} failed", method, e);
            return Response.of(200, error(id, McpError.INTERNAL_ERROR, messageOf(e), null));
        }
    }

    // --- HTTP binding -------------------------------------------------------------------------------------------

    /**
     * Against DNS rebinding: a browser request (it has an {@code Origin}) must come from a loopback origin. MCP
     * clients call from a process (a server, an IDE's extension host), which sends no {@code Origin}.
     */
    private boolean originAllowed(Map<String, Object> headers) {
        String origin = header(headers, "Origin");
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        try {
            String host = new URI(origin).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static void checkProtocolHeaders(String method, JsonNode params, Map<String, Object> headers) {
        String version = header(headers, "MCP-Protocol-Version");
        if (version == null) {
            throw McpError.headerMismatch("Missing MCP-Protocol-Version header");
        }
        JsonNode meta = params.get("_meta");
        if (meta == null || !meta.isObject()) {
            throw new McpError(McpError.INVALID_PARAMS, "params._meta is required", 400, null);
        }
        JsonNode bodyVersion = meta.get(META_PROTOCOL_VERSION);
        if (bodyVersion == null || !bodyVersion.isTextual()) {
            throw new McpError(McpError.INVALID_PARAMS, "params._meta['" + META_PROTOCOL_VERSION + "'] is required",
                    400, null);
        }
        if (!meta.path(META_CLIENT_CAPABILITIES).isObject()) {
            throw new McpError(McpError.INVALID_PARAMS, "params._meta['" + META_CLIENT_CAPABILITIES
                    + "'] is required", 400, null);
        }
        if (!version.equals(bodyVersion.asText())) {
            throw McpError.headerMismatch("MCP-Protocol-Version " + version + " does not match params._meta['"
                    + META_PROTOCOL_VERSION + "'] " + bodyVersion.asText());
        }
        if (!PROTOCOL_VERSION.equals(version)) {
            ObjectNode data = McpResults.MAPPER.createObjectNode();
            data.putArray("supported").add(PROTOCOL_VERSION);
            data.put("requested", version);
            throw new McpError(McpError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", 400, data);
        }
        String methodHeader = header(headers, "Mcp-Method");
        if (methodHeader == null) {
            throw McpError.headerMismatch("Missing Mcp-Method header");
        }
        if (!methodHeader.equals(method)) {
            throw McpError.headerMismatch("Mcp-Method " + methodHeader + " does not match method " + method);
        }
        String nameField = switch (method) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            default -> null;
        };
        if (nameField != null) {
            String nameHeader = decodeHeader(header(headers, "Mcp-Name"), "Mcp-Name");
            if (nameHeader == null) {
                throw McpError.headerMismatch("Missing Mcp-Name header");
            }
            String bodyName = params.path(nameField).asText(null);
            if (!nameHeader.equals(bodyName)) {
                throw McpError.headerMismatch("Mcp-Name " + nameHeader + " does not match params." + nameField
                        + " " + bodyName);
            }
        }
    }

    /** Every {@code x-mcp-header} argument present in the call must be mirrored, unchanged, in its header. */
    private static void checkParamHeaders(Tool tool, JsonNode arguments, Map<String, Object> headers) {
        for (ParamHeader param : tool.paramHeaders()) {
            JsonNode value = arguments;
            for (String key : param.path()) {
                value = value == null ? null : value.get(key);
            }
            if (value == null || value.isNull()) {
                continue;
            }
            String name = "Mcp-Param-" + param.header();
            String header = decodeHeader(header(headers, name), name);
            if (header == null) {
                throw McpError.headerMismatch("Missing " + name + " header");
            }
            boolean matches = switch (param.type()) {
                case "integer" -> value.isNumber() && numericEquals(header, value);
                case "boolean" -> value.isBoolean() && header.equals(value.asText());
                default -> value.isTextual() && header.equals(value.asText());
            };
            if (!matches) {
                throw McpError.headerMismatch(name + " does not match the argument " + String.join(".", param.path()));
            }
        }
    }

    private static boolean numericEquals(String header, JsonNode value) {
        try {
            return new BigDecimal(header).compareTo(value.decimalValue()) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Decodes the {@code =?base64?…?=} sentinel of the Streamable HTTP binding. */
    static String decodeHeader(String value, String name) {
        if (value == null || !(value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX))
                || value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length()) {
            return value;
        }
        String encoded = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
        // Java's decoder accepts missing padding; the binding does not
        if (encoded.length() % 4 != 0 || !BASE64.matcher(encoded).matches()) {
            throw McpError.headerMismatch(name + " is not valid base64");
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // --- authentication -----------------------------------------------------------------------------------------

    private Caller authenticate(McpServer.State state, Map<String, Object> headers) {
        McpAuthenticator authenticator = state.authenticator();
        if (authenticator == null) {
            return null;
        }
        Caller caller;
        try {
            caller = authenticator.authenticate(header(headers, "Authorization"));
        } catch (Unauthorized e) {
            StringBuilder challenge = new StringBuilder("Bearer resource_metadata=\"")
                    .append(metadataUrl(headers)).append('"');
            if (!authenticator.getAuth().scopes().isEmpty()) {
                challenge.append(", scope=\"").append(String.join(" ", authenticator.getAuth().scopes())).append('"');
            }
            if (e.isTokenPresent()) {
                challenge.append(", error=\"invalid_token\", error_description=\"")
                        .append(quoted(e.getMessage())).append('"');
            }
            throw new Rejected(new Response(401, Map.of("WWW-Authenticate", challenge.toString()), null));
        }
        requireScopes(caller, authenticator.getAuth().scopes(), headers);
        return caller;
    }

    private void requireScopes(Caller caller, List<String> scopes, Map<String, Object> headers) {
        if (caller == null || caller.hasScopes(scopes)) {
            return;
        }
        String challenge = "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", scopes)
                + "\", resource_metadata=\"" + metadataUrl(headers) + "\"";
        throw new Rejected(new Response(403, Map.of("WWW-Authenticate", challenge), null));
    }

    private void requireScopes(McpServer.State state, Caller caller, Capability capability,
            Map<String, Object> headers) {
        if (caller != null && !capability.scopes().isEmpty()) {
            requireScopes(caller, McpServer.union(state.definition().auth().scopes(), capability.scopes()), headers);
        }
    }

    private static boolean visible(Caller caller, Capability capability) {
        return caller == null || caller.hasScopes(capability.scopes());
    }

    static String baseUrl(Map<String, Object> headers) {
        String proto = forwardedOr(headers, "X-Forwarded-Proto", null);
        String host = forwardedOr(headers, "X-Forwarded-Host", "Host");
        return (proto == null ? "http" : proto) + "://" + (host == null ? "localhost" : host);
    }

    private static String metadataUrl(Map<String, Object> headers) {
        return baseUrl(headers) + METADATA_PATH;
    }

    private static String forwardedOr(Map<String, Object> headers, String forwarded, String fallback) {
        String value = header(headers, forwarded);
        if (value != null && !value.isEmpty()) {
            int comma = value.indexOf(',');
            return (comma < 0 ? value : value.substring(0, comma)).trim();
        }
        return fallback == null ? null : header(headers, fallback);
    }

    private static String quoted(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- methods ------------------------------------------------------------------------------------------------

    private ObjectNode dispatch(McpServer.State state, Caller caller, String method, JsonNode params,
            Map<String, Object> headers) {
        McpServerDefinition definition = state.definition();
        return switch (method) {
            case "server/discover" -> discover(definition);
            case "tools/list" -> list("tools", definition.tools(), caller, McpEndpoint::toolEntry);
            case "resources/list" -> list("resources", filter(definition.resources(), r -> r.uri() != null),
                    caller, McpEndpoint::resourceEntry);
            case "resources/templates/list" -> list("resourceTemplates",
                    filter(definition.resources(), r -> r.uriTemplate() != null), caller, McpEndpoint::templateEntry);
            case "prompts/list" -> list("prompts", definition.prompts(), caller, McpEndpoint::promptEntry);
            case "tools/call" -> callTool(state, caller, params, headers);
            case "resources/read" -> readResource(state, caller, params, headers);
            case "prompts/get" -> getPrompt(state, caller, params, headers);
            default -> throw new McpError(McpError.METHOD_NOT_FOUND, "Method not found: " + method, 404, null);
        };
    }

    private static ObjectNode discover(McpServerDefinition definition) {
        ObjectNode result = McpResults.MAPPER.createObjectNode();
        result.putArray("supportedVersions").add(PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        if (!definition.tools().isEmpty()) {
            capabilities.putObject("tools");
        }
        if (!definition.resources().isEmpty()) {
            capabilities.putObject("resources");
        }
        if (!definition.prompts().isEmpty()) {
            capabilities.putObject("prompts");
        }
        if (definition.info().instructions() != null) {
            result.put("instructions", definition.info().instructions());
        }
        cacheable(result, definition);
        // serverInfo goes to _meta on every result; discover adds the descriptive fields
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", definition.info().name());
        info.put("version", definition.info().version());
        putIfSet(info, "title", definition.info().title());
        putIfSet(info, "description", definition.info().description());
        return result;
    }

    private static <C extends Capability> ObjectNode list(String field, Map<String, C> capabilities, Caller caller,
            Function<C, ObjectNode> entry) {
        ObjectNode result = McpResults.MAPPER.createObjectNode();
        ArrayNode items = result.putArray(field);
        capabilities.values().stream().filter(c -> visible(caller, c)).map(entry).forEach(items::add);
        result.put("ttlMs", 0);
        result.put("cacheScope", caller == null ? "public" : "private");
        return result;
    }

    private static void cacheable(ObjectNode result, McpServerDefinition definition) {
        result.put("ttlMs", 0);
        result.put("cacheScope", definition.auth() == null ? "public" : "private");
    }

    private static <C> Map<String, C> filter(Map<String, C> map, java.util.function.Predicate<C> predicate) {
        Map<String, C> filtered = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (predicate.test(v)) {
                filtered.put(k, v);
            }
        });
        return filtered;
    }

    private static ObjectNode toolEntry(Tool tool) {
        ObjectNode entry = McpResults.MAPPER.createObjectNode();
        entry.put("name", tool.name());
        putIfSet(entry, "title", tool.title());
        entry.put("description", tool.description());
        entry.set("inputSchema", tool.inputSchema());
        if (tool.outputSchema() != null) {
            entry.set("outputSchema", tool.outputSchema());
        }
        if (tool.annotations() != null) {
            entry.set("annotations", tool.annotations());
        }
        return entry;
    }

    private static ObjectNode resourceEntry(Resource resource) {
        ObjectNode entry = McpResults.MAPPER.createObjectNode();
        entry.put("uri", resource.uri());
        entry.put("name", resource.name());
        entry.put("description", resource.description());
        entry.put("mimeType", resource.mimeType());
        return entry;
    }

    private static ObjectNode templateEntry(Resource resource) {
        ObjectNode entry = McpResults.MAPPER.createObjectNode();
        entry.put("uriTemplate", resource.uriTemplate().toString());
        entry.put("name", resource.name());
        entry.put("description", resource.description());
        entry.put("mimeType", resource.mimeType());
        return entry;
    }

    private static ObjectNode promptEntry(Prompt prompt) {
        ObjectNode entry = McpResults.MAPPER.createObjectNode();
        entry.put("name", prompt.name());
        entry.put("description", prompt.description());
        if (!prompt.arguments().isEmpty()) {
            ArrayNode arguments = entry.putArray("arguments");
            for (PromptArgument argument : prompt.arguments()) {
                ObjectNode item = arguments.addObject();
                item.put("name", argument.name());
                putIfSet(item, "description", argument.description());
                item.put("required", argument.required());
            }
        }
        return entry;
    }

    private ObjectNode callTool(McpServer.State state, Caller caller, JsonNode params, Map<String, Object> headers) {
        String name = params.path("name").asText();
        Tool tool = state.definition().tools().get(name);
        if (tool == null) {
            throw McpError.invalidParams("Unknown tool: " + name);
        }
        requireScopes(state, caller, tool, headers);
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = McpResults.MAPPER.createObjectNode();
        } else if (!arguments.isObject()) {
            throw McpError.invalidParams("params.arguments must be an object");
        }
        checkParamHeaders(tool, arguments, headers);
        // input validation errors are tool execution errors: the model can correct the call
        List<Error> errors = tool.inputValidator().validate(arguments);
        if (!errors.isEmpty()) {
            return McpResults.toolError("Invalid arguments: " + McpResults.describe(errors));
        }
        Exchange result = call(tool, "tools/call", toMap(arguments), caller, null);
        if (result.getException() != null) {
            LOG.warn("Tool {} failed", tool.name(), result.getException());
            return McpResults.toolError(messageOf(result.getException()));
        }
        return McpResults.toolResult(result, tool);
    }

    private ObjectNode readResource(McpServer.State state, Caller caller, JsonNode params,
            Map<String, Object> headers) {
        String uri = params.path("uri").asText(null);
        if (uri == null) {
            throw McpError.invalidParams("params.uri is required");
        }
        Resource resource = null;
        Map<String, String> variables = Map.of();
        for (Resource candidate : state.definition().resources().values()) {
            if (uri.equals(candidate.uri())) {
                resource = candidate;
                break;
            }
        }
        if (resource == null) {
            for (Resource candidate : state.definition().resources().values()) {
                Map<String, String> match = candidate.uriTemplate() == null ? null : candidate.uriTemplate().match(uri);
                if (match != null) {
                    resource = candidate;
                    variables = match;
                    break;
                }
            }
        }
        if (resource == null || !visible(caller, resource)) {
            ObjectNode data = McpResults.MAPPER.createObjectNode().put("uri", uri);
            throw new McpError(McpError.INVALID_PARAMS, "Resource not found", 200, data);
        }
        requireScopes(state, caller, resource, headers);
        Exchange result = call(resource, "resources/read", new LinkedHashMap<>(variables), caller, uri);
        if (result.getException() != null) {
            LOG.warn("Resource {} failed", resource.name(), result.getException());
            throw new McpError(McpError.INTERNAL_ERROR, messageOf(result.getException()), 200, null);
        }
        ObjectNode readResult = McpResults.resourceResult(result, resource, uri);
        cacheable(readResult, state.definition());
        return readResult;
    }

    private ObjectNode getPrompt(McpServer.State state, Caller caller, JsonNode params, Map<String, Object> headers) {
        String name = params.path("name").asText();
        Prompt prompt = state.definition().prompts().get(name);
        if (prompt == null) {
            throw McpError.invalidParams("Unknown prompt: " + name);
        }
        requireScopes(state, caller, prompt, headers);
        JsonNode arguments = params.path("arguments");
        if (!arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject()) {
            throw McpError.invalidParams("params.arguments must be an object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> argument : arguments.properties()) {
            if (prompt.arguments().stream().noneMatch(a -> a.name().equals(argument.getKey()))) {
                throw McpError.invalidParams("Unknown argument: " + argument.getKey());
            }
            if (!argument.getValue().isTextual()) {
                throw McpError.invalidParams("Argument " + argument.getKey() + " must be a string");
            }
            values.put(argument.getKey(), argument.getValue().asText());
        }
        for (PromptArgument argument : prompt.arguments()) {
            if (argument.required() && !values.containsKey(argument.name())) {
                throw McpError.invalidParams("Missing required argument: " + argument.name());
            }
        }
        Exchange result = call(prompt, "prompts/get", values, caller, null);
        if (result.getException() != null) {
            LOG.warn("Prompt {} failed", prompt.name(), result.getException());
            throw new McpError(McpError.INTERNAL_ERROR, messageOf(result.getException()), 200, null);
        }
        try {
            return McpResults.promptResult(result, prompt);
        } catch (IllegalArgumentException e) {
            throw new McpError(McpError.INTERNAL_ERROR, e.getMessage(), 200, null);
        }
    }

    /** Sends a new exchange to the capability's route (exchange contract: body and {@code Mcp*} headers). */
    private Exchange call(Capability capability, String method, Map<String, Object> body, Caller caller,
            String uri) {
        Exchange exchange = new DefaultExchange(server.context());
        Message in = exchange.getIn();
        in.setBody(body);
        in.setHeader(McpHeaders.METHOD, method);
        in.setHeader(McpHeaders.NAME, capability.name());
        if (uri != null) {
            in.setHeader(McpHeaders.URI, uri);
        }
        if (caller != null) {
            in.setHeader(McpHeaders.SUBJECT, caller.subject());
            in.setHeader(McpHeaders.SCOPES, List.copyOf(caller.scopes()));
            in.setHeader(McpHeaders.CLAIMS, caller.claims());
        }
        return server.template().send(capability.route(), exchange);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode arguments) {
        return McpResults.MAPPER.convertValue(arguments, LinkedHashMap.class);
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    static ObjectNode error(JsonNode id, int code, String message, Object data) {
        ObjectNode response = McpResults.MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", McpResults.MAPPER.valueToTree(data));
        }
        return response;
    }

    /** The exception's message only; the stack trace goes to the log. */
    private static String messageOf(Throwable e) {
        Throwable cause = e;
        while (cause.getMessage() == null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String header(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? null : value.toString();
    }

    private static void putIfSet(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
