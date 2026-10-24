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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An RFC 6570 level 1 URI template ({@code crm://customers/{id}}): literals and simple {@code {var}} expressions. A
 * level 1 expansion percent-encodes every reserved character, so a variable matches up to the next {@code /}, {@code ?}
 * or {@code #}.
 */
final class UriTemplate {

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final String template;
    private final List<String> variables;
    private final Pattern pattern;

    private UriTemplate(String template, List<String> variables, Pattern pattern) {
        this.template = template;
        this.variables = variables;
        this.pattern = pattern;
    }

    /**
     * @throws IllegalArgumentException with the reason when the template is not level 1 or a variable repeats
     */
    static UriTemplate parse(String template) {
        List<String> variables = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            int close = template.indexOf('}', i);
            if (open < 0) {
                if (close >= 0) {
                    throw new IllegalArgumentException("unmatched '}' at position " + close);
                }
                regex.append(Pattern.quote(template.substring(i)));
                break;
            }
            if (close >= 0 && close < open) {
                throw new IllegalArgumentException("unmatched '}' at position " + close);
            }
            if (close < 0) {
                throw new IllegalArgumentException("unclosed '{' at position " + open);
            }
            if (open > i) {
                regex.append(Pattern.quote(template.substring(i, open)));
            }
            String name = template.substring(open + 1, close);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'{" + name + "}' is not a level 1 expression {name}"
                        + " (letters, digits, underscore)");
            }
            if (variables.contains(name)) {
                throw new IllegalArgumentException("variable '" + name + "' appears twice");
            }
            variables.add(name);
            regex.append("([^/?#]+)");
            i = close + 1;
        }
        if (variables.isEmpty()) {
            throw new IllegalArgumentException("has no {variable}; use uri for a fixed resource");
        }
        return new UriTemplate(template, List.copyOf(variables), Pattern.compile(regex.toString()));
    }

    /**
     * @return the decoded variable values when the URI matches the template, otherwise {@code null}
     */
    Map<String, String> match(String uri) {
        Matcher matcher = pattern.matcher(uri);
        if (!matcher.matches()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (int g = 0; g < variables.size(); g++) {
                values.put(variables.get(g), decode(matcher.group(g + 1)));
            }
        } catch (IllegalArgumentException e) {
            return null; // malformed percent-encoding
        }
        return values;
    }

    private static String decode(String value) {
        // percent-decoding only: '+' is a literal plus in a URI, not a space
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    List<String> getVariables() {
        return variables;
    }

    @Override
    public String toString() {
        return template;
    }
}
