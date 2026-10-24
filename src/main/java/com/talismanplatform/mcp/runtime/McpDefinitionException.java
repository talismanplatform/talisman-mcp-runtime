/*
 * Copyright 2026 Talisman Platform
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

/**
 * {@code mcp-server.json} is missing or invalid. The message names the file, and for each problem the JSON path and
 * the reason.
 */
public class McpDefinitionException extends RuntimeException {

    private final List<String> problems;

    McpDefinitionException(String file, List<String> problems) {
        super(file + " is invalid:\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * @return one entry per problem, {@code <JSON path>: <reason>}
     */
    public List<String> getProblems() {
        return problems;
    }
}
