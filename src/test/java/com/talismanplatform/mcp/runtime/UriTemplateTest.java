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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UriTemplateTest {

    @Test
    void matchesAndDecodes() {
        UriTemplate template = UriTemplate.parse("db://{schema}/tables/{table}");
        assertEquals(Map.of("schema", "public", "table", "my table+x"),
                template.match("db://public/tables/my%20table+x"));
        assertNull(template.match("db://public/tables/a/b"));
        assertNull(template.match("db://public/views/a"));
        assertNull(template.match("db://public/tables/%zz"));
    }

    @Test
    void escapesLiterals() {
        assertEquals(Map.of("id", "1"), UriTemplate.parse("a.b://x?y={id}").match("a.b://x?y=1"));
        assertNull(UriTemplate.parse("a.b://{id}").match("aXb://1"));
    }

    @Test
    void rejectsWhatIsNotLevelOne() {
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse("x://{+path}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse("x://{a,b}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse("x://{a"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse("x://{a}/{a}"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse("x://fixed"));
    }
}
