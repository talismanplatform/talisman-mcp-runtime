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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.talismanplatform.mcp.runtime.McpTestServer.Reply;

import static com.talismanplatform.mcp.runtime.McpTestServer.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bearer tokens checked against a stub issuer: its OpenID configuration and JWKS served by a JDK HttpServer. */
class McpAuthTest {

    private static HttpServer issuerServer;
    private static String issuer;
    private static RSAKey key;
    private static RSAKey otherKey;
    private static McpTestServer server;

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        issuerServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer = "http://localhost:" + issuerServer.getAddress().getPort() + "/realms/test";
        String discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/certs\"}";
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        serve("/realms/test/.well-known/openid-configuration", discovery);
        serve("/realms/test/certs", jwks);
        issuerServer.start();
        server = McpTestServer.start("mcp/auth.json", Map.of(
                "camel.oauth.mcp.base-uri", issuer,
                "camel.oauth.mcp.expected-audience", "mcp-test",
                "camel.oauth.mcp.allow-insecure-http", "true"));
    }

    private static void serve(String path, String json) {
        issuerServer.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        issuerServer.stop(0);
    }

    private static String token(RSAKey signingKey, String audience, String scope, long expiresInSeconds)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("alice")
                .claim("scope", scope)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return "Bearer " + jwt.serialize();
    }

    private static String token(String scope) throws Exception {
        return token(key, "mcp-test", scope, 300);
    }

    @Test
    void noTokenIsAChallenge() throws Exception {
        Reply reply = server.call("tools/list", null);
        assertEquals(401, reply.status());
        assertNull(reply.body());
        String challenge = reply.header("WWW-Authenticate");
        assertTrue(challenge.startsWith("Bearer resource_metadata=\"" + server.url("") + "/.well-known/oauth-protected-resource\""),
                challenge);
        assertTrue(challenge.contains("scope=\"mcp\""), challenge);
        assertTrue(!challenge.contains("invalid_token"), challenge);
    }

    @Test
    void invalidTokens() throws Exception {
        assertInvalidToken(server.call("tools/list", null, "Authorization", token(otherKey, "mcp-test", "mcp", 300)),
                "The token's signature is not valid");
        assertInvalidToken(server.call("tools/list", null, "Authorization", "Bearer not-a-jwt"),
                "The token is not valid");
    }

    @Test
    void wrongAudience() throws Exception {
        assertInvalidToken(server.call("tools/list", null, "Authorization", token(key, "account", "mcp", 300)),
                "The token is for another audience");
    }

    @Test
    void expiredToken() throws Exception {
        assertInvalidToken(server.call("tools/list", null, "Authorization", token(key, "mcp-test", "mcp", -600)),
                "The token has expired");
    }

    private static void assertInvalidToken(Reply reply, String description) {
        assertEquals(401, reply.status());
        String challenge = reply.header("WWW-Authenticate");
        assertTrue(challenge.startsWith("Bearer resource_metadata=\""), challenge);
        assertTrue(challenge.contains("scope=\"mcp\""), challenge);
        assertTrue(challenge.contains("error=\"invalid_token\""), challenge);
        assertTrue(challenge.endsWith("error_description=\"" + description + "\""), challenge);
    }

    @Test
    void validTokenReachesTheRouteAsHeaders() throws Exception {
        Reply reply = server.call("tools/call", params("name", "headers"), "Authorization", token("mcp extra"));
        assertEquals(200, reply.status(), String.valueOf(reply.body()));
        JsonNode seen = reply.result().path("structuredContent");
        assertEquals("alice", seen.path("subject").asText());
        assertEquals(List.of("extra", "mcp"), McpTestServer.MAPPER.convertValue(seen.path("scopes"), List.class));
        assertTrue(seen.path("aud").toString().contains("mcp-test"), seen.toString());
        assertTrue(seen.path("authorization").isNull());
        assertEquals("private", server.call("tools/list", null, "Authorization", token("mcp")).result()
                .path("cacheScope").asText());
    }

    @Test
    void missingScopeIsForbidden() throws Exception {
        Reply reply = server.call("tools/list", null, "Authorization", token("other"));
        assertEquals(403, reply.status());
        String challenge = reply.header("WWW-Authenticate");
        assertTrue(challenge.contains("error=\"insufficient_scope\""), challenge);
        assertTrue(challenge.contains("scope=\"mcp\""), challenge);
    }

    @Test
    void listsAreNotFilteredByScope() throws Exception {
        JsonNode tools = server.call("tools/list", null, "Authorization", token("mcp")).result().path("tools");
        assertEquals(2, tools.size());
        assertEquals(200, server.call("tools/call", params("name", "admin"), "Authorization", token("mcp"))
                .status());
    }

    @Test
    void protectedResourceMetadata() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            Reply reply = server.get(path);
            assertEquals(200, reply.status(), path);
            JsonNode metadata = reply.body();
            assertEquals(server.url("/mcp"), metadata.path("resource").asText());
            assertEquals(issuer, metadata.path("authorization_servers").get(0).asText());
            assertEquals("mcp", metadata.path("scopes_supported").get(0).asText());
            assertEquals("header", metadata.path("bearer_methods_supported").get(0).asText());
        }
    }

    @Test
    void forwardedHeadersSetTheResource() throws Exception {
        Reply reply = server.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(server.url("/.well-known/oauth-protected-resource")))
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "mcp.example.com").GET().build());
        assertEquals("https://mcp.example.com/mcp", reply.body().path("resource").asText());
    }
}
