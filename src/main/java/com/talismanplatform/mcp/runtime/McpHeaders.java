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

/**
 * Headers the runtime sets on the exchange it sends to a capability's {@code direct:} route.
 */
public final class McpHeaders {

    /** The MCP method, e.g. {@code tools/call}. */
    public static final String METHOD = "McpMethod";
    /** The capability's name (the key in {@code mcp-server.json}). */
    public static final String NAME = "McpName";
    /** The requested resource URI ({@code resources/read} only). */
    public static final String URI = "McpUri";
    /** The token's {@code sub} claim, when authentication is on. */
    public static final String SUBJECT = "McpSubject";
    /** The token's scopes as a {@code List<String>}, when authentication is on. */
    public static final String SCOPES = "McpScopes";
    /** All claims of the token as a {@code Map<String, Object>}, when authentication is on. */
    public static final String CLAIMS = "McpClaims";

    private McpHeaders() {
    }
}
