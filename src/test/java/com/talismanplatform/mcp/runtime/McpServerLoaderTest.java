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

import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.engine.DefaultContextServiceLoaderPlugin;
import org.apache.camel.spi.ContextServiceLoaderPluginResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading and checking mcp-server.json. */
class McpServerLoaderTest {

    private CamelContext context;

    @BeforeEach
    void createContext() {
        // the loader on its own: no MCP server, which would need mcp-server.json on the classpath to start
        context = new DefaultCamelContext() {
            @Override
            protected ContextServiceLoaderPluginResolver createContextServiceLoaderPlugin() {
                return new DefaultContextServiceLoaderPlugin() {
                    @Override
                    protected void doStart() {
                    }
                };
            }
        };
    }

    @AfterEach
    void stopContext() {
        context.stop();
    }

    @Test
    void loadsAValidDefinition() {
        McpServerDefinition definition = McpServerLoader.load(context, "mcp/basic.json");
        assertEquals("test-server", definition.info().name());
        assertEquals(List.of("greet", "add", "wrongOutput", "fail", "headers", "region"),
                List.copyOf(definition.tools().keySet()));
        assertEquals(3, definition.tools().get("region").paramHeaders().size());
        assertEquals("customer://{id}", definition.resourceTemplates().get("customer").uriTemplate().toString());
        assertEquals("Configuration", definition.resources().get("config").title());
        assertNull(definition.resources().get("untyped").mimeType());
        assertTrue(McpServerLoader.warnings(definition).isEmpty());
        assertTrue(definition.prompts().get("review").arguments().get(0).required());
    }

    @Test
    void placeholdersAreNotResolved() {
        McpServerDefinition definition = McpServerLoader.load(context, "mcp/auth.json");
        assertEquals("mcp", definition.auth().oauthProfile());
        assertEquals(List.of("mcp"), definition.auth().scopes());
    }

    @Test
    void aNameInBothResourceSectionsIsAWarning() {
        McpServerDefinition definition = McpServerLoader.load(context, "mcp/warnings.json");
        assertEquals(List.of("$.resourceTemplates.customer: customer is also the name of a resource"),
                McpServerLoader.warnings(definition));
    }

    @Test
    void missingFile() {
        McpDefinitionException e = assertThrows(McpDefinitionException.class,
                () -> McpServerLoader.load(context, "mcp/none.json"));
        assertTrue(e.getMessage().contains("mcp/none.json"), e.getMessage());
    }

    @Test
    void syntaxErrorHasALine() {
        List<String> problems = problems("mcp/syntax-error.json");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("line 5"), problems.toString());
    }

    @Test
    void duplicateKey() {
        assertTrue(problems("mcp/duplicate-key.json").get(0).contains("name"));
    }

    @Test
    void schemaErrors() {
        String problems = String.join("\n", problems("mcp/schema-errors.json"));
        assertTrue(problems.contains("$.info: required property 'version' not found"), problems);
        assertTrue(problems.contains("$: property 'extra' is not defined"), problems);
        assertTrue(problems.contains("$.tools: property 'bad name' name is not valid"), problems);
        assertTrue(problems.contains("$.tools['bad name'].route: does not match"), problems);
        assertTrue(problems.contains("$.tools['bad name'].inputSchema.type: must be the constant value 'object'"),
                problems);
        assertTrue(problems.contains("$.tools['bad name']: property 'scopes' is not defined"), problems);
        assertTrue(problems.contains("$.resources.family: required property 'uri' not found"), problems);
        assertTrue(problems.contains("$.resourceTemplates.fixed: required property 'uriTemplate' not found"), problems);
        assertTrue(problems.contains("$.auth: required property 'oauthProfile' not found"), problems);
    }

    @Test
    void semanticErrors() {
        String problems = String.join("\n", problems("mcp/semantic-errors.json"));
        assertTrue(problems.contains("$.tools.badSchema.inputSchema.properties.a.type: "), problems);
        assertTrue(problems.contains("x-mcp-header same is used twice"), problems);
        assertTrue(problems.contains("$.tools.badHeaders.inputSchema.properties.c['x-mcp-header']: the property's type"),
                problems);
        assertTrue(problems.contains("properties.d['x-mcp-header']: must be an HTTP header name token"), problems);
        assertTrue(problems.contains("properties.e.items['x-mcp-header']: allowed only on a property reached from the root"), problems);
        assertTrue(!problems.contains("Data"), problems);
        assertTrue(problems.contains("$.resources.two.uri: same://x is also the uri of resource one"), problems);
        assertTrue(problems.contains("$.resources.noScheme.uri: relative/path has no scheme"), problems);
        assertTrue(problems.contains("$.resourceTemplates.fixedTemplate.uriTemplate"), problems);
        assertTrue(problems.contains("$.prompts.dup.arguments[1].name"), problems);
    }

    @Test
    void everyRouteNeedsADirectConsumer() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct://greet?timeout=1000").setBody(constant("x"));
                from("direct:add").setBody(constant("x"));
            }
        });
        context.start();
        McpServerDefinition definition = McpServerLoader.load(context, "mcp/basic.json");
        McpDefinitionException e = assertThrows(McpDefinitionException.class,
                () -> McpServerLoader.checkRoutes(definition, context.getRoutes(), "mcp/basic.json"));
        String problems = String.join("\n", e.getProblems());
        assertTrue(problems.contains("$.tools.fail.route: no route consumes direct:fail"), problems);
        assertTrue(problems.contains("$.resources.config.route"), problems);
        assertTrue(problems.contains("$.prompts.review.route"), problems);
        assertTrue(!problems.contains("$.tools.greet."), problems);
        assertTrue(!problems.contains("$.tools.add."), problems);
    }

    @Test
    void directName() {
        assertEquals("a", McpServerLoader.directName("direct:a"));
        assertEquals("a", McpServerLoader.directName("direct://a?timeout=1"));
    }

    private List<String> problems(String file) {
        return assertThrows(McpDefinitionException.class, () -> McpServerLoader.load(context, file)).getProblems();
    }
}
