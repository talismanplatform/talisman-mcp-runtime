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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the official MCP conformance suite ({@code @modelcontextprotocol/conformance}) against the runtime at
 * protocol 2026-07-28. Needs Node.js and network access, so it runs only with {@code -Dmcp.conformance=true}.
 * Scenarios for what the runtime does not serve (completion, progress, SSE, image/audio/embedded content,
 * input-required results) are not listed; {@code conformance-baseline.yml} names the checks that need them.
 */
@EnabledIfSystemProperty(named = "mcp.conformance", matches = "true")
class McpConformanceTest {

    static final String PACKAGE = "@modelcontextprotocol/conformance@0.2.0-alpha.12";

    private static McpTestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = McpTestServer.start("mcp/conformance.json", Map.of());
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "server-stateless",
            "tools-list",
            "tools-call-simple-text",
            "tools-call-error",
            "json-schema-2020-12",
            "resources-list",
            "resources-read-text",
            "resources-read-binary",
            "resources-templates-read",
            "sep-2164-resource-not-found",
            "prompts-list",
            "prompts-get-simple",
            "prompts-get-with-args",
            "dns-rebinding-protection",
            "caching",
            "http-header-validation",
            "http-custom-header-server-validation" })
    void scenario(String scenario) throws Exception {
        Path dir = Path.of("target", "conformance", scenario);
        Files.createDirectories(dir);
        Path baseline = Path.of("src", "test", "resources", "mcp", "conformance-baseline.yml").toAbsolutePath();
        Process process = new ProcessBuilder("npx", "--yes", PACKAGE, "server", "--url", server.url("/mcp"),
                "--scenario", scenario, "--spec-version", McpEndpoint.PROTOCOL_VERSION,
                "--expected-failures", baseline.toString(), "--output-dir", ".")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("output.txt").toFile())
                .start();
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), scenario + " timed out");
        List<String> failures = failures(dir);
        assertEquals(0, process.exitValue(), () -> scenario + " failed, see " + dir.resolve("output.txt") + ":\n  "
                + String.join("\n  ", failures));
    }

    private static List<String> failures(Path dir) throws Exception {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path checks : files.filter(f -> f.getFileName().toString().equals("checks.json")).toList()) {
                for (JsonNode check : McpTestServer.MAPPER.readTree(checks.toFile())) {
                    String status = check.path("status").asText();
                    if (status.equals("FAILURE") || status.equals("WARNING")) {
                        failures.add(status + " " + check.path("id").asText() + ": "
                                + check.path("errorMessage").asText());
                    }
                }
            }
        }
        return failures;
    }
}
