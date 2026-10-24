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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;

import com.talismanplatform.mcp.runtime.McpServerDefinition.Auth;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Capability;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Info;
import com.talismanplatform.mcp.runtime.McpServerDefinition.ParamHeader;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Prompt;
import com.talismanplatform.mcp.runtime.McpServerDefinition.PromptArgument;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Resource;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Tool;

/**
 * Reads {@code mcp-server.json} from the classpath: resolves property placeholders, validates it against the bundled
 * {@code mcp-server.schema.json} and the rules the schema cannot express, and checks that every route has a consumer.
 */
final class McpServerLoader {

    static final String SCHEMA_RESOURCE = "mcp-server.schema.json";
    private static final String DEFAULT_META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";
    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Keywords whose values are data, not subschemas: an {@code x-mcp-header} inside them is not an annotation. */
    private static final Set<String> DATA_KEYWORDS = Set.of("const", "default", "enum", "examples");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> META_SCHEMAS = new ConcurrentHashMap<>();
    private static volatile Schema fileSchema;

    private McpServerLoader() {
    }

    /**
     * @throws McpDefinitionException when the file is missing, is not JSON, or is invalid
     */
    static McpServerDefinition load(CamelContext context, String file) {
        JsonNode root = read(context, file);
        List<String> problems = new ArrayList<>();
        root = resolvePlaceholders(context, root, "$", problems);
        if (problems.isEmpty()) {
            for (Error error : fileSchema().validate(root)) {
                problems.add(describe("$", error));
            }
        }
        if (!problems.isEmpty()) {
            throw new McpDefinitionException(file, problems);
        }
        McpServerDefinition definition = build((ObjectNode) root, problems);
        if (!problems.isEmpty()) {
            throw new McpDefinitionException(file, problems);
        }
        return definition;
    }

    /**
     * Every capability's route must be a {@code direct:} endpoint some route of the context consumes.
     *
     * @throws McpDefinitionException naming each capability whose route has no consumer
     */
    static void checkRoutes(McpServerDefinition definition, Collection<Route> routes, String file) {
        Set<String> consumers = new HashSet<>();
        for (Route route : routes) {
            String uri = route.getEndpoint().getEndpointUri();
            if (uri.startsWith("direct:")) {
                consumers.add(directName(uri));
            }
        }
        List<String> problems = new ArrayList<>();
        checkRoutes("tools", definition.tools(), consumers, problems);
        checkRoutes("resources", definition.resources(), consumers, problems);
        checkRoutes("prompts", definition.prompts(), consumers, problems);
        if (!problems.isEmpty()) {
            throw new McpDefinitionException(file, problems);
        }
    }

    private static void checkRoutes(String section, Map<String, ? extends Capability> capabilities,
            Set<String> consumers, List<String> problems) {
        capabilities.values().forEach(capability -> {
            if (!consumers.contains(directName(capability.route()))) {
                problems.add(path(path(path("$", section), capability.name()), "route") + ": no route consumes "
                        + capability.route());
            }
        });
    }

    /** {@code direct:foo}, {@code direct://foo} and {@code direct://foo?x=y} are all {@code foo}. */
    static String directName(String uri) {
        String name = uri.substring("direct:".length());
        if (name.startsWith("//")) {
            name = name.substring(2);
        }
        int query = name.indexOf('?');
        return query < 0 ? name : name.substring(0, query);
    }

    private static JsonNode read(CamelContext context, String file) {
        InputStream in = context.getClassResolver().loadResourceAsStream(file);
        if (in == null) {
            throw new McpDefinitionException(file, List.of("$: not found on the classpath"));
        }
        try (in) {
            JsonNode root = MAPPER.readTree(in);
            if (root == null || !root.isObject()) {
                throw new McpDefinitionException(file, List.of("$: must be a JSON object"));
            }
            return root;
        } catch (JsonProcessingException e) {
            JsonLocation location = e.getLocation();
            String where = location == null ? ""
                    : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
            throw new McpDefinitionException(file, List.of("$: not valid JSON: " + e.getOriginalMessage() + where));
        } catch (IOException e) {
            throw new McpDefinitionException(file, List.of("$: cannot be read: " + e.getMessage()));
        }
    }

    /** Resolves {@code {{key}}} in every string value (not in keys) against the integration's properties. */
    private static JsonNode resolvePlaceholders(CamelContext context, JsonNode node, String path,
            List<String> problems) {
        if (node.isTextual()) {
            String text = node.textValue();
            if (!text.contains("{{")) {
                return node;
            }
            try {
                return TextNode.valueOf(context.resolvePropertyPlaceholders(text));
            } catch (RuntimeException e) {
                problems.add(path + ": " + e.getMessage());
                return node;
            }
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                field.setValue(resolvePlaceholders(context, field.getValue(), path(path, field.getKey()), problems));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, resolvePlaceholders(context, array.get(i), path + "[" + i + "]", problems));
            }
        }
        return node;
    }

    private static McpServerDefinition build(ObjectNode root, List<String> problems) {
        JsonNode info = root.get("info");
        Info serverInfo = new Info(text(info, "name"), text(info, "version"), text(info, "title"),
                text(info, "description"), text(info, "instructions"));

        Auth auth = null;
        JsonNode authNode = root.get("auth");
        if (authNode != null) {
            auth = new Auth(text(authNode, "issuer"), text(authNode, "audience"), strings(authNode.get("scopes")));
            checkIssuer(auth.issuer(), problems);
        }

        Map<String, Tool> tools = new LinkedHashMap<>();
        forEach(root.get("tools"), (name, node) -> {
            String path = path(path("$", "tools"), name);
            ObjectNode input = (ObjectNode) node.get("inputSchema");
            ObjectNode output = (ObjectNode) node.get("outputSchema");
            Schema inputValidator = compile(input, path(path, "inputSchema"), problems);
            Schema outputValidator = output == null ? null : compile(output, path(path, "outputSchema"), problems);
            List<ParamHeader> headers = paramHeaders(input, path(path, "inputSchema"), problems);
            tools.put(name, new Tool(name, text(node, "route"), text(node, "title"), text(node, "description"),
                    input, output, (ObjectNode) node.get("annotations"), strings(node.get("scopes")),
                    inputValidator, outputValidator, headers));
        });

        Map<String, Resource> resources = new LinkedHashMap<>();
        Map<String, String> uris = new HashMap<>();
        forEach(root.get("resources"), (name, node) -> {
            String path = path(path("$", "resources"), name);
            String uri = text(node, "uri");
            UriTemplate template = null;
            if (uri != null) {
                checkUri(uri, path(path, "uri"), problems);
                String other = uris.putIfAbsent(uri, name);
                if (other != null) {
                    problems.add(path(path, "uri") + ": " + uri + " is also the uri of resource " + other);
                }
            } else {
                String templateText = text(node, "uriTemplate");
                try {
                    template = UriTemplate.parse(templateText);
                } catch (IllegalArgumentException e) {
                    problems.add(path(path, "uriTemplate") + ": " + templateText + " " + e.getMessage());
                }
            }
            resources.put(name, new Resource(name, text(node, "route"), uri, template, text(node, "mimeType"),
                    text(node, "description"), strings(node.get("scopes"))));
        });

        Map<String, Prompt> prompts = new LinkedHashMap<>();
        forEach(root.get("prompts"), (name, node) -> {
            String path = path(path("$", "prompts"), name);
            List<PromptArgument> arguments = new ArrayList<>();
            Set<String> argumentNames = new HashSet<>();
            JsonNode argumentsNode = node.get("arguments");
            if (argumentsNode != null) {
                for (int i = 0; i < argumentsNode.size(); i++) {
                    JsonNode argument = argumentsNode.get(i);
                    String argumentName = text(argument, "name");
                    if (!argumentNames.add(argumentName)) {
                        problems.add(path(path, "arguments") + "[" + i + "].name: " + argumentName
                                + " appears twice");
                    }
                    arguments.add(new PromptArgument(argumentName, text(argument, "description"),
                            argument.path("required").asBoolean(false)));
                }
            }
            prompts.put(name, new Prompt(name, text(node, "route"), text(node, "description"),
                    List.copyOf(arguments), strings(node.get("scopes"))));
        });

        return new McpServerDefinition(serverInfo, auth, tools, resources, prompts);
    }

    private static void checkIssuer(String issuer, List<String> problems) {
        try {
            URI uri = new URI(issuer);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
                problems.add("$.auth.issuer: " + issuer + " is not an http(s) URL");
            }
        } catch (URISyntaxException e) {
            problems.add("$.auth.issuer: " + issuer + " is not a URL: " + e.getMessage());
        }
    }

    private static void checkUri(String value, String path, List<String> problems) {
        try {
            if (new URI(value).getScheme() == null) {
                problems.add(path + ": " + value + " has no scheme");
            }
        } catch (URISyntaxException e) {
            problems.add(path + ": " + value + " is not a URI: " + e.getMessage());
        }
    }

    /** Validates an inline schema against its meta-schema and compiles it. */
    private static Schema compile(ObjectNode schema, String path, List<String> problems) {
        String metaSchema = schema.path("$schema").asText(DEFAULT_META_SCHEMA);
        try {
            Schema meta = META_SCHEMAS.computeIfAbsent(metaSchema,
                    uri -> REGISTRY.getSchema(SchemaLocation.of(uri)));
            List<Error> errors = meta.validate(schema);
            if (!errors.isEmpty()) {
                errors.forEach(error -> problems.add(describe(path, error)));
                return null;
            }
            return REGISTRY.getSchema(schema);
        } catch (RuntimeException e) {
            problems.add(path + ": not a usable JSON Schema: " + e.getMessage());
            return null;
        }
    }

    /**
     * Collects the {@code x-mcp-header} annotations of a tool's input schema and checks the constraints of the
     * Streamable HTTP binding: a header token, unique ignoring case, on a string, integer or boolean property that is
     * reachable from the root through {@code properties} only.
     */
    private static List<ParamHeader> paramHeaders(ObjectNode inputSchema, String path, List<String> problems) {
        List<ParamHeader> headers = new ArrayList<>();
        collectParamHeaders(inputSchema, List.of(), true, path, headers, problems);
        Set<String> seen = new HashSet<>();
        for (ParamHeader header : headers) {
            if (!seen.add(header.header().toLowerCase(Locale.ROOT))) {
                problems.add(path + ": x-mcp-header " + header.header() + " is used twice");
            }
        }
        return List.copyOf(headers);
    }

    private static void collectParamHeaders(JsonNode node, List<String> propertyPath, boolean reachable, String path,
            List<ParamHeader> headers, List<String> problems) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectParamHeaders(node.get(i), propertyPath, false, path + "[" + i + "]", headers, problems);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode annotation = node.get("x-mcp-header");
        if (annotation != null) {
            String where = path(path, "x-mcp-header");
            String type = node.path("type").asText("");
            if (!reachable || propertyPath.isEmpty()) {
                problems.add(where + ": allowed only on a property reached from the root through properties");
            } else if (!annotation.isTextual() || !HEADER_TOKEN.matcher(annotation.asText()).matches()) {
                problems.add(where + ": must be an HTTP header name token");
            } else if (!(type.equals("string") || type.equals("integer") || type.equals("boolean"))) {
                problems.add(where + ": the property's type must be string, integer or boolean");
            } else {
                headers.add(new ParamHeader(annotation.asText(), propertyPath, type));
            }
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (DATA_KEYWORDS.contains(key) || key.equals("x-mcp-header")) {
                continue;
            }
            if (key.equals("properties") && value.isObject()) {
                for (Map.Entry<String, JsonNode> property : value.properties()) {
                    List<String> childPath = new ArrayList<>(propertyPath);
                    childPath.add(property.getKey());
                    collectParamHeaders(property.getValue(), List.copyOf(childPath), reachable,
                            path(path(path, "properties"), property.getKey()), headers, problems);
                }
            } else {
                collectParamHeaders(value, propertyPath, false, path(path, key), headers, problems);
            }
        }
    }

    private static Schema fileSchema() {
        Schema schema = fileSchema;
        if (schema == null) {
            try (InputStream in = McpServerLoader.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the library");
                }
                schema = REGISTRY.getSchema(MAPPER.readTree(in));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + SCHEMA_RESOURCE, e);
            }
            fileSchema = schema;
        }
        return schema;
    }

    private static String describe(String base, Error error) {
        return McpResults.location(base, error) + ": " + error.getMessage();
    }

    /** {@code $.tools.get_customer}, or {@code $.tools['get-customer']} when the key is not a plain name. */
    static String path(String base, String key) {
        return SIMPLE_KEY.matcher(key).matches() ? base + "." + key : base + "['" + key + "']";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static void forEach(JsonNode section, java.util.function.BiConsumer<String, JsonNode> action) {
        if (section != null) {
            section.properties().forEach(field -> action.accept(field.getKey(), field.getValue()));
        }
    }
}
