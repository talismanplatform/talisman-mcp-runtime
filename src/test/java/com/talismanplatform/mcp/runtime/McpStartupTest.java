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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An invalid definition stops the integration. */
class McpStartupTest {

    @Test
    void missingRouteConsumerStopsStartup() {
        String message = startupFailure("mcp/missing-route.json", Map.of());
        assertTrue(message.contains("$.tools.nowhere.route: no route consumes direct:nowhere"), message);
    }

    @Test
    void aProfileWithoutIssuerStopsStartup() {
        String message = startupFailure("mcp/auth.json", Map.of("camel.oauth.mcp.expected-audience", "mcp-test"));
        assertTrue(message.contains("$.auth.oauthProfile: camel.oauth.mcp.*: set base-uri or expected-issuer"),
                message);
    }

    @Test
    void aProfileWithoutAudienceStopsStartup() {
        String message = startupFailure("mcp/auth.json", Map.of(
                "camel.oauth.mcp.expected-issuer", "https://issuer.example",
                "camel.oauth.mcp.jwks-endpoint", "https://issuer.example/certs"));
        assertTrue(message.contains("$.auth.oauthProfile: camel.oauth.mcp.*: Token validation requires expected-audience"),
                message);
    }

    private static String startupFailure(String file, Map<String, String> properties) {
        Exception e = assertThrows(Exception.class, () -> McpTestServer.start(file, properties).close());
        Throwable cause = e;
        while (cause != null && !(cause instanceof McpDefinitionException)) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "not caused by McpDefinitionException: " + e);
        return cause.getMessage();
    }
}
