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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.talismanplatform.mcp.runtime.McpTestServer.Reply;

import static com.talismanplatform.mcp.runtime.McpTestServer.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP methods and the Streamable HTTP binding of a server without authentication. */
class McpEndpointTest {

    private static McpTestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = McpTestServer.start("mcp/basic.json", Map.of());
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    // --- methods ------------------------------------------------------------------------------------------------

    @Test
    void discover() throws Exception {
        Reply reply = server.call("server/discover", null);
        assertEquals(200, reply.status());
        assertEquals("application/json", reply.header("Content-Type"));
        JsonNode result = reply.result();
        assertEquals("2026-07-28", result.path("supportedVersions").get(0).asText());
        assertTrue(result.path("capabilities").has("tools"));
        assertTrue(result.path("capabilities").has("resources"));
        assertTrue(result.path("capabilities").has("prompts"));
        assertEquals("Use greet to say hello.", result.path("instructions").asText());
        assertEquals("Test Server", result.path("serverInfo").path("title").asText());
        assertEquals("complete", result.path("resultType").asText());
        assertEquals("public", result.path("cacheScope").asText());
        assertEquals("test-server",
                result.path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asText());
    }

    @Test
    void listTools() throws Exception {
        JsonNode tools = server.call("tools/list", null).result().path("tools");
        assertEquals(6, tools.size());
        JsonNode greet = tools.get(0);
        assertEquals("greet", greet.path("name").asText());
        assertEquals("Says hello", greet.path("description").asText());
        assertEquals("object", greet.path("inputSchema").path("type").asText());
        assertTrue(greet.path("annotations").path("readOnlyHint").asBoolean());
        assertEquals("integer", tools.get(1).path("outputSchema").path("properties").path("sum").path("type").asText());
    }

    @Test
    void callToolWithTextResult() throws Exception {
        JsonNode result = server.call("tools/call", params("name", "greet", "arguments", Map.of("name", "Ada"))).result();
        assertEquals("Hello Ada", result.path("content").get(0).path("text").asText());
        assertFalse(result.has("isError"));
        assertFalse(result.has("structuredContent"));
    }

    @Test
    void callToolWithStructuredResult() throws Exception {
        JsonNode result = server.call("tools/call", params("name", "add", "arguments", Map.of("a", 2, "b", 3))).result();
        assertEquals(5, result.path("structuredContent").path("sum").asInt());
        assertEquals("{\"sum\":5}", result.path("content").get(0).path("text").asText());
    }

    @Test
    void invalidArgumentsAreAToolError() throws Exception {
        JsonNode result = server.call("tools/call", params("name", "add", "arguments", Map.of("a", "two"))).result();
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().startsWith("Invalid arguments: "),
                result.toString());
    }

    @Test
    void resultNotMatchingTheOutputSchemaIsAToolError() throws Exception {
        JsonNode result = server.call("tools/call", params("name", "wrongOutput")).result();
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("outputSchema"));
    }

    @Test
    void failingRouteIsAToolErrorWithTheMessage() throws Exception {
        JsonNode result = server.call("tools/call", params("name", "fail")).result();
        assertTrue(result.path("isError").asBoolean());
        assertEquals("boom", result.path("content").get(0).path("text").asText());
    }

    @Test
    void unknownToolIsInvalidParams() throws Exception {
        Reply reply = server.call("tools/call", params("name", "nope"));
        assertEquals(200, reply.status());
        assertEquals(-32602, reply.error().path("code").asInt());
    }

    @Test
    void routeGetsTheArgumentsAndMcpHeadersButNoRequestHeaders() throws Exception {
        JsonNode seen = server.call("tools/call", params("name", "headers", "arguments", Map.of("x", 1)),
                "Authorization", "Bearer secret").result().path("structuredContent");
        assertEquals("tools/call", seen.path("method").asText());
        assertEquals("headers", seen.path("name").asText());
        assertEquals(1, seen.path("arguments").path("x").asInt());
        assertTrue(seen.path("authorization").isNull());
        assertTrue(seen.path("subject").isNull());
    }

    @Test
    void routeExchangeIsCorrelatedWithTheHttpExchange() throws Exception {
        JsonNode seen = server.call("tools/call", params("name", "headers"), "breadcrumbId", "trace-1")
                .result().path("structuredContent");
        assertTrue(seen.path("correlationId").isTextual());
        assertNotEquals(seen.path("exchangeId").asText(), seen.path("correlationId").asText());
        assertEquals("trace-1", seen.path("breadcrumbId").asText());
    }

    @Test
    void listResourcesAndTemplates() throws Exception {
        JsonNode resources = server.call("resources/list", null).result().path("resources");
        assertEquals(2, resources.size());
        assertEquals("config://app", resources.get(0).path("uri").asText());
        assertEquals("text/plain", resources.get(0).path("mimeType").asText());
        JsonNode templates = server.call("resources/templates/list", null).result().path("resourceTemplates");
        assertEquals(1, templates.size());
        assertEquals("customer://{id}", templates.get(0).path("uriTemplate").asText());
    }

    @Test
    void readTextResource() throws Exception {
        JsonNode content = server.call("resources/read", params("uri", "config://app")).result().path("contents").get(0);
        assertEquals("config://app", content.path("uri").asText());
        assertEquals("key=value", content.path("text").asText());
    }

    @Test
    void readTemplatedResource() throws Exception {
        JsonNode content = server.call("resources/read", params("uri", "customer://a%20b")).result()
                .path("contents").get(0);
        JsonNode customer = McpTestServer.MAPPER.readTree(content.path("text").asText());
        assertEquals("a b", customer.path("id").asText());
        assertEquals("customer://a%20b", customer.path("uri").asText());
        assertEquals("application/json", content.path("mimeType").asText());
    }

    @Test
    void readBinaryResource() throws Exception {
        JsonNode content = server.call("resources/read", params("uri", "file:///logo.png")).result()
                .path("contents").get(0);
        assertEquals(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }), content.path("blob").asText());
        assertFalse(content.has("text"));
    }

    @Test
    void unknownResource() throws Exception {
        JsonNode error = server.call("resources/read", params("uri", "nothing://here")).error();
        assertEquals(-32602, error.path("code").asInt());
        assertEquals("nothing://here", error.path("data").path("uri").asText());
    }

    @Test
    void listPrompts() throws Exception {
        JsonNode prompt = server.call("prompts/list", null).result().path("prompts").get(0);
        assertEquals("review", prompt.path("name").asText());
        assertTrue(prompt.path("arguments").get(0).path("required").asBoolean());
        assertFalse(prompt.path("arguments").get(1).path("required").asBoolean());
    }

    @Test
    void getPrompt() throws Exception {
        JsonNode result = server.call("prompts/get",
                params("name", "review", "arguments", Map.of("code", "x = 1", "language", "Python"))).result();
        assertEquals("Reviews code", result.path("description").asText());
        JsonNode messages = result.path("messages");
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("Review this Python:\nx = 1", messages.get(0).path("content").path("text").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
    }

    @Test
    void promptArgumentErrors() throws Exception {
        assertEquals(-32602, server.call("prompts/get", params("name", "review")).error().path("code").asInt());
        assertEquals(-32602, server.call("prompts/get",
                params("name", "review", "arguments", Map.of("code", "x", "other", "y"))).error().path("code").asInt());
        assertEquals(-32602, server.call("prompts/get",
                params("name", "review", "arguments", Map.of("code", 1))).error().path("code").asInt());
    }

    // --- binding ------------------------------------------------------------------------------------------------

    @Test
    void unknownMethod() throws Exception {
        Reply reply = server.call("sampling/createMessage", null);
        assertEquals(404, reply.status());
        assertEquals(-32601, reply.error().path("code").asInt());
    }

    @Test
    void initializeIsARemovedMethod() throws Exception {
        Reply reply = server.call("initialize", params("protocolVersion", "2025-06-18"));
        assertEquals(404, reply.status());
        assertEquals(-32601, reply.error().path("code").asInt());
        assertEquals("2026-07-28", reply.error().path("data").path("supported").get(0).asText());
    }

    @Test
    void notificationIsAccepted() throws Exception {
        Reply reply = server.post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{}}", Map.of());
        assertEquals(202, reply.status());
        assertNull(reply.body());
    }

    @Test
    void getIsNotAllowed() throws Exception {
        Reply reply = server.get("/mcp");
        assertEquals(405, reply.status());
        assertEquals("POST", reply.header("Allow"));
    }

    @Test
    void parseErrorAndInvalidRequest() throws Exception {
        Reply parse = server.post("{not json", Map.of());
        assertEquals(400, parse.status());
        assertEquals(-32700, parse.error().path("code").asInt());
        Reply batch = server.post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]", Map.of());
        assertEquals(400, batch.status());
        assertEquals(-32600, batch.error().path("code").asInt());
        Reply version = server.post("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}", Map.of());
        assertEquals(-32600, version.error().path("code").asInt());
    }

    @Test
    void protocolHeadersAreRequiredAndMustMatch() throws Exception {
        ObjectNode request = server.request("tools/list", null);
        assertHeaderMismatch(server.post(request.toString(), Map.of("Mcp-Method", "tools/list")));
        assertHeaderMismatch(server.post(request.toString(), Map.of("MCP-Protocol-Version", "2026-07-28")));
        assertHeaderMismatch(server.post(request.toString(),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "prompts/list")));
        Reply call = server.call("tools/call", params("name", "greet", "arguments", Map.of("name", "x")),
                "Mcp-Name", "add");
        assertHeaderMismatch(call);
    }

    @Test
    void metaIsRequired() throws Exception {
        Map<String, String> headers = Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list");
        ObjectNode noMeta = server.request("tools/list", null);
        noMeta.with("params").remove("_meta");
        assertInvalidMeta(server.post(noMeta.toString(), headers));
        ObjectNode noCapabilities = server.request("tools/list", null);
        noCapabilities.with("params").with("_meta").remove("io.modelcontextprotocol/clientCapabilities");
        assertInvalidMeta(server.post(noCapabilities.toString(), headers));
        ObjectNode noVersion = server.request("tools/list", null);
        noVersion.with("params").with("_meta").remove("io.modelcontextprotocol/protocolVersion");
        assertInvalidMeta(server.post(noVersion.toString(), headers));
    }

    private static void assertInvalidMeta(Reply reply) {
        assertEquals(400, reply.status(), String.valueOf(reply.body()));
        assertEquals(-32602, reply.error().path("code").asInt());
    }

    @Test
    void unsupportedVersion() throws Exception {
        ObjectNode request = server.request("tools/list", null);
        request.with("params").with("_meta").put("io.modelcontextprotocol/protocolVersion", "2099-01-01");
        Reply reply = server.post(request.toString(), Map.of("MCP-Protocol-Version", "2099-01-01", "Mcp-Method", "tools/list"));
        assertEquals(400, reply.status());
        assertEquals(-32022, reply.error().path("code").asInt());
    }

    @Test
    void base64EncodedName() throws Exception {
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("greet".getBytes(StandardCharsets.UTF_8)) + "?=";
        Reply reply = server.call("tools/call", params("name", "greet", "arguments", Map.of("name", "x")),
                "Mcp-Name", encoded);
        assertEquals("Hello x", reply.result().path("content").get(0).path("text").asText());
    }

    @Test
    void paramHeadersMustMirrorTheArguments() throws Exception {
        ObjectNode params = params("name", "region", "arguments", Map.of("name", "eu", "count", 3, "dry", true));
        Reply ok = server.call("tools/call", params,
                "Mcp-Param-Region", "eu", "Mcp-Param-Count", "3.0", "Mcp-Param-Dry", "true");
        assertEquals(200, ok.status(), String.valueOf(ok.body()));
        assertEquals("Hello eu", ok.result().path("content").get(0).path("text").asText());
        assertHeaderMismatch(server.call("tools/call", params, "Mcp-Param-Region", "eu", "Mcp-Param-Count", "3"));
        assertHeaderMismatch(server.call("tools/call", params,
                "Mcp-Param-Region", "us", "Mcp-Param-Count", "3", "Mcp-Param-Dry", "true"));
        Reply absent = server.call("tools/call", params("name", "region", "arguments", Map.of()));
        assertEquals(200, absent.status());
    }

    @Test
    void base64MustBePadded() throws Exception {
        ObjectNode params = params("name", "region", "arguments", Map.of("name", "Hello"));
        assertEquals(200, server.call("tools/call", params, "Mcp-Param-Region", "=?base64?SGVsbG8=?=").status());
        assertHeaderMismatch(server.call("tools/call", params, "Mcp-Param-Region", "=?base64?SGVsbG8?="));
        assertHeaderMismatch(server.call("tools/call", params, "Mcp-Param-Region", "=?base64?SGVs*G8=?="));
    }

    @Test
    void origin() throws Exception {
        assertEquals(403, server.call("tools/list", null, "Origin", "https://evil.example").status());
        assertEquals(200, server.call("tools/list", null, "Origin", server.url("")).status());
        assertEquals(200, server.call("tools/list", null, "Origin", "http://127.0.0.1:1234").status());
        // DNS rebinding: evil.example resolves to the server, so the Host matches the Origin
        assertEquals(403, server.call("tools/list", null, "Origin", "http://evil.example", "X-Forwarded-Host",
                "evil.example").status());
    }

    @Test
    void noProtectedResourceMetadataWithoutAuth() throws Exception {
        assertEquals(404, server.get("/.well-known/oauth-protected-resource").status());
    }

    private static void assertHeaderMismatch(Reply reply) {
        assertEquals(400, reply.status(), String.valueOf(reply.body()));
        assertEquals(-32020, reply.error().path("code").asInt());
    }
}
