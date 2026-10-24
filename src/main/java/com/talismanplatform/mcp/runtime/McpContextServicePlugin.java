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

import org.apache.camel.CamelContext;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.spi.ContextServicePlugin;

/**
 * Entry point, found through {@code META-INF/services}: having the JAR on the classpath makes the integration an
 * MCP server on {@code /mcp}, defined by {@code mcp-server.json} on the classpath.
 */
public class McpContextServicePlugin implements ContextServicePlugin {

    private McpServer server;

    @Override
    public void load(CamelContext context) {
        server = new McpServer(context);
        try {
            context.addRoutes(new McpRoutes(server));
            context.addStartupListener(server);
        } catch (Exception e) {
            throw RuntimeCamelException.wrapRuntimeCamelException(e);
        }
    }

    @Override
    public void onReload(CamelContext context) {
        if (server != null) {
            server.reload();
        }
    }

    @Override
    public void unload(CamelContext context) {
        if (server != null) {
            server.stop();
        }
    }
}
