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
import org.apache.camel.ProducerTemplate;
import org.apache.camel.StartupListener;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelEvent.RouteReloadedEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MCP definition of the running integration. Loaded when the context starts (an invalid definition stops the
 * integration) and re-read on dev mode's reload (an invalid definition is logged and the previous one is kept).
 * <p>
 * A reload re-reads the file once Camel has re-added every route (the last {@link RouteReloadedEvent} of the batch):
 * {@code ContextServicePlugin.onReload} comes before the routes are removed and re-added, and the
 * {@link StartupListener} is called again for each route started meanwhile, so neither sees the new set of routes.
 * A reload that removes all routes (Camel Main's default) also removes the runtime's own routes, which come from no
 * file; they are added again first.
 */
final class McpServer implements StartupListener {

    static final String DEFAULT_FILE = "mcp-server.json";

    private static final Logger LOG = LoggerFactory.getLogger(McpServer.class);

    /** What a request is served with; replaced as a whole on reload. */
    record State(McpServerDefinition definition, McpAuthenticator authenticator) {
    }

    private final CamelContext context;
    private volatile State state;
    private volatile ProducerTemplate template;

    McpServer(CamelContext context) {
        this.context = context;
    }

    @Override
    public void onCamelContextStarted(CamelContext context, boolean alreadyStarted) {
        if (alreadyStarted) {
            return; // a route started on the running context, e.g. during a reload: see reloadNotifier
        }
        state = load();
        log(state.definition());
    }

    /** Reloads the definition after the last route of a reload batch. */
    EventNotifierSupport reloadNotifier() {
        return new EventNotifierSupport() {
            @Override
            public boolean isEnabled(CamelEvent event) {
                return event instanceof RouteReloadedEvent;
            }

            @Override
            public void notify(CamelEvent event) {
                RouteReloadedEvent reloaded = (RouteReloadedEvent) event;
                if (reloaded.getIndex() == reloaded.getTotal()) {
                    restoreRoutes();
                    reload();
                }
            }
        };
    }

    private void restoreRoutes() {
        if (context.getRoute(McpRoutes.MCP_ROUTE_ID) != null) {
            return;
        }
        try {
            context.addRoutes(new McpRoutes(this));
        } catch (Exception e) {
            LOG.error("Adding the MCP routes again after the reload failed: /mcp is not served", e);
        }
    }

    /** Re-reads the definition; keeps serving the previous one when the new one is invalid. */
    void reload() {
        if (state == null) {
            return; // not started yet: the start loads it
        }
        try {
            state = load();
            log(state.definition());
        } catch (McpDefinitionException e) {
            LOG.error("{}\nThe MCP server keeps the previous definition.", e.getMessage());
        }
    }

    private State load() {
        McpServerDefinition definition = McpServerLoader.load(context, DEFAULT_FILE);
        McpServerLoader.checkRoutes(definition, context.getRoutes(), DEFAULT_FILE);
        McpAuthenticator authenticator = definition.auth() == null ? null
                : new McpAuthenticator(context, definition.auth(), DEFAULT_FILE);
        McpServerLoader.warnings(definition).forEach(warning -> LOG.warn("{}: {}", DEFAULT_FILE, warning));
        return new State(definition, authenticator);
    }

    private static void log(McpServerDefinition definition) {
        LOG.info("MCP server {} {} on /mcp: {} tools, {} resources, {} resource templates, {} prompts, {}",
                definition.info().name(), definition.info().version(), definition.tools().size(),
                definition.resources().size(), definition.resourceTemplates().size(), definition.prompts().size(),
                definition.auth() == null ? "no authentication"
                        : "tokens checked by OAuth profile " + definition.auth().oauthProfile());
    }

    /**
     * @return the current state, or {@code null} before the context has started
     */
    State state() {
        return state;
    }

    CamelContext context() {
        return context;
    }

    ProducerTemplate template() {
        ProducerTemplate current = template;
        if (current == null) {
            synchronized (this) {
                if (template == null) {
                    template = context.createProducerTemplate();
                }
                current = template;
            }
        }
        return current;
    }

    void stop() {
        ProducerTemplate current = template;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException e) {
                LOG.debug("Stopping the producer template failed", e);
            }
        }
    }
}
