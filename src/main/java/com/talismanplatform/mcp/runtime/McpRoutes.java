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

import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;

import com.talismanplatform.mcp.runtime.McpEndpoint.Response;
import com.talismanplatform.mcp.runtime.McpServerDefinition.Auth;

/**
 * The HTTP routes of the MCP server: {@code /mcp} and the protected resource metadata (RFC 9728).
 */
final class McpRoutes extends RouteBuilder {

    static final String MCP_ROUTE_ID = "mcp-server";
    static final String METADATA_ROUTE_ID = "mcp-protected-resource-metadata";

    private final McpServer server;

    McpRoutes(McpServer server) {
        this.server = server;
    }

    @Override
    public void configure() {
        from("platform-http:/mcp").routeId(MCP_ROUTE_ID)
                .process(new McpEndpoint(server));

        from("platform-http:" + McpEndpoint.METADATA_PATH + "?httpMethodRestrict=GET")
                .routeId(METADATA_ROUTE_ID)
                .process(this::metadata);
        from("platform-http:" + McpEndpoint.METADATA_PATH + "/mcp?httpMethodRestrict=GET")
                .routeId(METADATA_ROUTE_ID + "-mcp")
                .process(this::metadata);
    }

    private void metadata(Exchange exchange) {
        Message message = exchange.getMessage();
        Map<String, Object> headers = McpEndpoint.requestHeaders(message);
        McpServer.State state = server.state();
        Auth auth = state == null ? null : state.definition().auth();
        if (auth == null) {
            McpEndpoint.write(message, new Response(404, Map.of(), null));
            return;
        }
        ObjectNode metadata = McpResults.MAPPER.createObjectNode();
        metadata.put("resource", McpEndpoint.baseUrl(headers) + "/mcp");
        metadata.putArray("authorization_servers").add(auth.issuer());
        if (!auth.scopes().isEmpty()) {
            auth.scopes().forEach(metadata.putArray("scopes_supported")::add);
        }
        metadata.putArray("bearer_methods_supported").add("header");
        String name = state.definition().info().title();
        metadata.put("resource_name", name == null ? state.definition().info().name() : name);
        McpEndpoint.write(message, Response.of(200, metadata));
    }
}
