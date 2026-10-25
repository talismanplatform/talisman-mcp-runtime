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
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.engine.DefaultClassResolver;
import org.apache.camel.main.BaseMainSupport;
import org.apache.camel.main.Main;
import org.apache.camel.main.MainListenerSupport;

/** A Camel Main integration with the runtime on the classpath, and a client that speaks MCP to it. */
final class McpTestServer implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** An HTTP reply; {@code body} is null when there is none. */
    record Reply(int status, HttpResponse<String> response, JsonNode body) {
        String header(String name) {
            return response.headers().firstValue(name).orElse(null);
        }

        JsonNode result() {
            return body.get("result");
        }

        JsonNode error() {
            return body.get("error");
        }
    }

    private final Main main;
    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private int nextId = 1;

    private McpTestServer(Main main, int port) {
        this.main = main;
        this.port = port;
    }

    static McpTestServer start(String file, Map<String, String> properties) throws Exception {
        int port = freePort();
        Main main = new Main();
        main.addInitialProperty("camel.server.enabled", "true");
        main.addInitialProperty("camel.server.port", String.valueOf(port));
        main.addMainListener(new MainListenerSupport() {
            @Override
            public void beforeConfigure(BaseMainSupport main) {
                CamelContext context = main.getCamelContext();
                context.setClassResolver(new FixtureClassResolver(context, file));
            }
        });
        properties.forEach(main::addInitialProperty);
        main.configure().addRoutesBuilder(new TestRoutes());
        main.start();
        return new McpTestServer(main, port);
    }

    /**
     * A dev-mode-like integration: the routes are the {@code *.yaml} files of {@code dir} and
     * {@value McpServer#DEFAULT_FILE} is read from {@code dir}, so a test can change both and reload.
     */
    static McpTestServer startFrom(Path dir) throws Exception {
        int port = freePort();
        Main main = new Main();
        main.addInitialProperty("camel.server.enabled", "true");
        main.addInitialProperty("camel.server.port", String.valueOf(port));
        main.addInitialProperty("camel.main.routesIncludePattern", "file:" + dir + "/*.yaml");
        main.addMainListener(new MainListenerSupport() {
            @Override
            public void beforeConfigure(BaseMainSupport main) {
                CamelContext context = main.getCamelContext();
                context.setClassResolver(new DirectoryClassResolver(context, dir));
            }
        });
        main.start();
        return new McpTestServer(main, port);
    }

    CamelContext context() {
        return main.getCamelContext();
    }

    /** Reads {@value McpServer#DEFAULT_FILE} from a directory instead of the classpath. */
    private static final class DirectoryClassResolver extends DefaultClassResolver {

        private final Path dir;

        DirectoryClassResolver(CamelContext context, Path dir) {
            super(context);
            this.dir = dir;
        }

        @Override
        public InputStream loadResourceAsStream(String uri) {
            if (!McpServer.DEFAULT_FILE.equals(uri)) {
                return super.loadResourceAsStream(uri);
            }
            try {
                return Files.newInputStream(dir.resolve(uri));
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Serves {@code file} as the integration's {@value McpServer#DEFAULT_FILE}. */
    private static final class FixtureClassResolver extends DefaultClassResolver {

        private final String file;

        FixtureClassResolver(CamelContext context, String file) {
            super(context);
            this.file = file;
        }

        @Override
        public InputStream loadResourceAsStream(String uri) {
            return super.loadResourceAsStream(McpServer.DEFAULT_FILE.equals(uri) ? file : uri);
        }

        @Override
        public URL loadResourceAsURL(String uri) {
            return super.loadResourceAsURL(McpServer.DEFAULT_FILE.equals(uri) ? file : uri);
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** A request with every header the protocol asks for. */
    Reply call(String method, ObjectNode params, String... headers) throws Exception {
        ObjectNode request = request(method, params);
        Map<String, String> all = new LinkedHashMap<>(protocolHeaders(method, params));
        for (int i = 0; i < headers.length; i += 2) {
            all.put(headers[i], headers[i + 1]);
        }
        return post(request.toString(), all);
    }

    ObjectNode request(String method, ObjectNode params) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", nextId++);
        request.put("method", method);
        ObjectNode withMeta = params == null ? MAPPER.createObjectNode() : params.deepCopy();
        ObjectNode meta = withMeta.with("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", McpEndpoint.PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        request.set("params", withMeta);
        return request;
    }

    static Map<String, String> protocolHeaders(String method, ObjectNode params) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("MCP-Protocol-Version", McpEndpoint.PROTOCOL_VERSION);
        headers.put("Mcp-Method", method);
        if (params != null && method.equals("resources/read")) {
            headers.put("Mcp-Name", params.path("uri").asText());
        } else if (params != null && params.has("name")) {
            headers.put("Mcp-Name", params.path("name").asText());
        }
        return headers;
    }

    Reply post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("/mcp")))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    Reply get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))).GET().build());
    }

    Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isEmpty() ? null : MAPPER.readTree(response.body());
        return new Reply(response.statusCode(), response, body);
    }

    static ObjectNode params(Object... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.set((String) keyValues[i], MAPPER.valueToTree(keyValues[i + 1]));
        }
        return node;
    }

    @Override
    public void close() throws Exception {
        main.stop();
    }
}
