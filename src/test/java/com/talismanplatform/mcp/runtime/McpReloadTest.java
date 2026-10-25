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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.support.RouteOnDemandReloadStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Dev mode's reload: Camel notifies the runtime before it removes and re-adds the routes. */
class McpReloadTest {

    private static final String NEW_TOOL = route("newTool");
    private static final String DEMO = route("demo");

    @TempDir
    Path dir;

    @Test
    void aNewToolAndItsRouteAreServedAfterTheReload() throws Exception {
        Files.writeString(dir.resolve("newTool.camel.yaml"), NEW_TOOL);
        Files.writeString(dir.resolve(McpServer.DEFAULT_FILE), definition("new_tool", "newTool"));
        try (McpTestServer server = McpTestServer.startFrom(dir)) {
            assertEquals(List.of("new_tool"), toolNames(server));

            Files.writeString(dir.resolve("demo.camel.yaml"), DEMO);
            Files.writeString(dir.resolve(McpServer.DEFAULT_FILE),
                    definition("new_tool", "newTool", "new_tool_2", "demo"));
            RouteOnDemandReloadStrategy reload = reload(server);

            assertNull(reload.getLastError(), () -> "the route reload failed: " + reload.getLastError());
            assertEquals(List.of("new_tool", "new_tool_2"), toolNames(server));
        }
    }

    @Test
    void anInvalidDefinitionKeepsThePreviousOneAndDoesNotFailTheRouteReload() throws Exception {
        Files.writeString(dir.resolve("newTool.camel.yaml"), NEW_TOOL);
        Files.writeString(dir.resolve(McpServer.DEFAULT_FILE), definition("new_tool", "newTool"));
        try (McpTestServer server = McpTestServer.startFrom(dir)) {
            Files.writeString(dir.resolve(McpServer.DEFAULT_FILE),
                    definition("new_tool", "newTool", "new_tool_2", "nowhere"));
            RouteOnDemandReloadStrategy reload = reload(server);

            assertNull(reload.getLastError(), () -> "the route reload failed: " + reload.getLastError());
            assertEquals(List.of("new_tool"), toolNames(server));
        }
    }

    @Test
    void aReloadThatKeepsTheOtherRoutesServesTheNewTool() throws Exception {
        Files.writeString(dir.resolve("newTool.camel.yaml"), NEW_TOOL);
        Files.writeString(dir.resolve(McpServer.DEFAULT_FILE), definition("new_tool", "newTool"));
        try (McpTestServer server = McpTestServer.startFrom(dir)) {
            Files.writeString(dir.resolve("demo.camel.yaml"), DEMO);
            Files.writeString(dir.resolve(McpServer.DEFAULT_FILE),
                    definition("new_tool", "newTool", "new_tool_2", "demo"));
            RouteOnDemandReloadStrategy reload = reload(server, false);

            assertNull(reload.getLastError(), () -> "the route reload failed: " + reload.getLastError());
            assertEquals(List.of("new_tool", "new_tool_2"), toolNames(server));
        }
    }

    private RouteOnDemandReloadStrategy reload(McpTestServer server) throws Exception {
        return reload(server, true);
    }

    /** As dev mode's reload; {@code removeAllRoutes} is Camel Main's {@code routesReloadRemoveAllRoutes}. */
    private RouteOnDemandReloadStrategy reload(McpTestServer server, boolean removeAllRoutes) throws Exception {
        RouteOnDemandReloadStrategy reload = new RouteOnDemandReloadStrategy(dir.toString(), false);
        reload.setRemoveAllRoutes(removeAllRoutes);
        reload.setPattern("*.yaml");
        server.context().addService(reload);
        reload.onReload("test");
        return reload;
    }

    private static List<String> toolNames(McpTestServer server) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : server.call("tools/list", null).result().path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static String definition(String... toolsAndRoutes) {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < toolsAndRoutes.length; i += 2) {
            tools.append(i == 0 ? "" : ",").append("\"").append(toolsAndRoutes[i])
                    .append("\": {\"route\": \"direct:").append(toolsAndRoutes[i + 1])
                    .append("\", \"description\": \"d\", \"inputSchema\": {\"type\": \"object\"}}");
        }
        return "{\"info\": {\"name\": \"reload\", \"version\": \"1.0.0\"}, \"tools\": {" + tools + "}}";
    }

    private static String route(String name) {
        return """
                - route:
                    id: %s
                    from:
                      uri: direct:%s
                      steps:
                        - setBody:
                            constant: ok
                """.formatted(name, name);
    }
}
