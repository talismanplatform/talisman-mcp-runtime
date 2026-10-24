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
        Exception e = assertThrows(Exception.class, () -> McpTestServer.start("mcp/missing-route.json", Map.of()).close());
        Throwable cause = e;
        while (cause != null && !(cause instanceof McpDefinitionException)) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "not caused by McpDefinitionException: " + e);
        assertTrue(cause.getMessage().contains("$.tools.nowhere.route: no route consumes direct:nowhere"),
                cause.getMessage());
    }
}
