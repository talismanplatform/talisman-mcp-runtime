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
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;

/**
 * {@code mcp-server.json} after validation. Maps keep the file's order.
 */
record McpServerDefinition(Info info, Auth auth, Map<String, Tool> tools, Map<String, Resource> resources,
        Map<String, ResourceTemplate> resourceTemplates, Map<String, Prompt> prompts) {

    record Info(String name, String version, String title, String description, String instructions) {
    }

    /**
     * @param oauthProfile the camel-oauth profile ({@code camel.oauth.<profile>.*}) that validates the token
     * @param scopes       the scopes the token must have for every call
     */
    record Auth(String oauthProfile, List<String> scopes) {
    }

    /** A capability: a name and the route that implements it. */
    interface Capability {
        String name();

        String route();
    }

    /** What {@code resources/read} needs of a resource or a resource template. */
    interface Readable extends Capability {
        String mimeType();
    }

    /**
     * @param paramHeaders the {@code x-mcp-header} annotations of {@code inputSchema}
     */
    record Tool(String name, String route, String title, String description, ObjectNode inputSchema,
            ObjectNode outputSchema, ObjectNode annotations, Schema inputValidator,
            Schema outputValidator, List<ParamHeader> paramHeaders) implements Capability {
    }

    /**
     * A tool argument mirrored into the {@code Mcp-Param-<header>} HTTP header.
     *
     * @param path the chain of {@code properties} keys from the schema root to the argument
     * @param type {@code string}, {@code integer} or {@code boolean}
     */
    record ParamHeader(String header, List<String> path, String type) {
    }

    /** A fixed resource; {@code mimeType} may be {@code null}. */
    record Resource(String name, String route, String uri, String title, String description, String mimeType)
            implements Readable {
    }

    /** A family of resources; {@code mimeType} may be {@code null}. */
    record ResourceTemplate(String name, String route, UriTemplate uriTemplate, String title, String description,
            String mimeType) implements Readable {
    }

    record Prompt(String name, String route, String description, List<PromptArgument> arguments)
            implements Capability {
    }

    record PromptArgument(String name, String description, boolean required) {
    }
}
