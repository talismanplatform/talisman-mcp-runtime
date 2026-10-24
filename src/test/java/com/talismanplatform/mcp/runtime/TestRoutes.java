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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.builder.RouteBuilder;

/** The direct: routes behind the test definitions. */
class TestRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:greet").process(e -> e.getMessage().setBody("Hello " + e.getMessage().getBody(Map.class).get("name")));
        from("direct:add").process(e -> {
            Map<?, ?> args = e.getMessage().getBody(Map.class);
            int sum = ((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue();
            e.getMessage().setBody(Map.of("sum", sum));
        });
        from("direct:wrongOutput").setBody(constant(Map.of("total", 1)));
        from("direct:fail").throwException(new IllegalStateException("boom"));
        from("direct:headers").process(e -> {
            Map<String, Object> seen = new LinkedHashMap<>();
            seen.put("method", e.getMessage().getHeader(McpHeaders.METHOD));
            seen.put("name", e.getMessage().getHeader(McpHeaders.NAME));
            seen.put("subject", e.getMessage().getHeader(McpHeaders.SUBJECT));
            List<?> scopes = e.getMessage().getHeader(McpHeaders.SCOPES, List.class);
            seen.put("scopes", scopes == null ? null : List.copyOf(new TreeSet<Object>(scopes)));
            Map<?, ?> claims = e.getMessage().getHeader(McpHeaders.CLAIMS, Map.class);
            seen.put("aud", claims == null ? null : claims.get("aud"));
            seen.put("authorization", e.getMessage().getHeader("Authorization"));
            seen.put("arguments", e.getMessage().getBody());
            seen.put("correlationId", e.getProperty(ExchangePropertyKey.CORRELATION_ID));
            seen.put("exchangeId", e.getExchangeId());
            seen.put("breadcrumbId", e.getMessage().getHeader(Exchange.BREADCRUMB_ID));
            e.getMessage().setBody(seen);
        });
        from("direct:config").setBody(constant("key=value"));
        from("direct:customer").process(e -> {
            Map<?, ?> variables = e.getMessage().getBody(Map.class);
            e.getMessage().setBody(Map.of("id", variables.get("id"),
                    "uri", e.getMessage().getHeader(McpHeaders.URI)));
        });
        from("direct:logo").setBody(constant(new byte[] { 1, 2, 3 }));
        from("direct:conformance-text").setBody(constant("This is a simple text response for testing."));
        from("direct:conformance-prompt").process(e -> {
            Map<?, ?> args = e.getMessage().getBody(Map.class);
            e.getMessage().setBody(args.isEmpty() ? "This is a simple prompt for testing."
                    : "Prompt with arguments: arg1='" + args.get("arg1") + "', arg2='" + args.get("arg2") + "'");
        });
        from("direct:review").process(e -> {
            Map<?, ?> args = e.getMessage().getBody(Map.class);
            e.getMessage().setBody(List.of(
                    "Review this " + (args.containsKey("language") ? args.get("language") : "code") + ":\n" + args.get("code"),
                    Map.of("role", "assistant", "content", "Looking at it.")));
        });
    }
}
