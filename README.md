# Talisman MCP Runtime

[![Maven Central](https://img.shields.io/maven-central/v/com.talismanplatform/talisman-mcp-runtime)](https://central.sonatype.com/artifact/com.talismanplatform/talisman-mcp-runtime)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/talismanplatform/talisman-mcp-runtime/actions/workflows/build.yml/badge.svg)](https://github.com/talismanplatform/talisman-mcp-runtime/actions/workflows/build.yml)

Turn an Apache Camel integration into a [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server.
AI agents get tools, resources and prompts; each one is a Camel route, so it can use any of Camel's components.

Developed by [Talisman Platform](https://talismanplatform.com), where it powers MCP projects. It works standalone with
any Camel Main integration.

## Why

An MCP server is mostly integration work: call an API, query a database, read a queue, map the result. Camel already
does that. This library adds the MCP side and nothing else:

- **The full server surface.** Tools, resources, resource templates and prompts.
- **One declarative file.** `mcp-server.json` describes the server. It is validated against a published JSON Schema
  at startup, together with the routes it points to, and every problem is reported with its JSON path.
- **Production auth.** OAuth 2.0 bearer tokens checked by camel-oauth, scopes, and protected resource metadata
  (RFC 9728).
- **Stateless.** No sessions, so instances scale horizontally behind any load balancer.
- **Verified.** The build runs the official MCP conformance suite.

Apache Camel's own `camel-mcp-server` (Preview since Camel 4.22) exposes routes as MCP tools. Use this runtime when you
need resources and prompts too, a validated server definition, or token-based access control.

## Compatibility

| Runtime | MCP protocol | Apache Camel | Java |
|---|---|---|---|
| 26.16.x | 2026-07-28 (Streamable HTTP, stateless) | 4.22.x (Camel Main) | 25+ |

Only MCP 2026-07-28 is served. Clients that start with `initialize` (protocol 2025-11-25 and earlier) cannot connect.

## Quick start

Three files in one folder.

`mcp-server.json`:

```json
{
  "info": { "name": "hello", "version": "1.0.0" },
  "tools": {
    "greet": {
      "route": "direct:greet",
      "description": "Greets a person by name",
      "inputSchema": { "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] }
    }
  }
}
```

`greet.camel.yaml`:

```yaml
- route:
    from:
      uri: direct:greet
      steps:
        - setBody:
            expression:
              simple:
                expression: "Hello, ${body[name]}!"
```

`application.properties`:

```properties
camel.server.enabled=true
camel.server.port=8080
```

Run it with the [Camel CLI](https://camel.apache.org/manual/camel-jbang.html):

```bash
camel run greet.camel.yaml mcp-server.json application.properties \
  --dep=mvn:com.talismanplatform:talisman-mcp-runtime:26.16.0
```

Call the tool:

```bash
curl -s localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: greet' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"greet","arguments":{"name":"Ada"},
       "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Hello, Ada!"}],"resultType":"complete", …}}
```

Any MCP client that speaks 2026-07-28 can now use `http://localhost:8080/mcp`.

In a Maven project, add the dependency instead:

```xml
<dependency>
    <groupId>com.talismanplatform</groupId>
    <artifactId>talisman-mcp-runtime</artifactId>
    <version>26.16.0</version>
</dependency>
```

The dependency alone activates the runtime: it registers a Camel `ContextServicePlugin` and serves `/mcp` on Camel's
platform-http server, which must be enabled (`camel.server.enabled=true`).

## `mcp-server.json`

Read from the classpath when the context starts. Its JSON Schema ships at the root of the JAR as
`mcp-server.schema.json` (`$id` `https://talismanplatform.com/schemas/mcp-server.schema.json`).

```json
{
  "info": { "name": "crm", "version": "1.0.0", "title": "CRM", "instructions": "Look customers up by id." },
  "auth": { "oauthProfile": "mcp", "scopes": ["crm"] },
  "tools": {
    "get_customer": {
      "route": "direct:getCustomer",
      "description": "Returns a customer",
      "inputSchema": { "type": "object", "properties": { "id": { "type": "string" } }, "required": ["id"] },
      "annotations": { "readOnlyHint": true }
    }
  },
  "resources": {
    "segments": { "route": "direct:listSegments", "uri": "crm://segments", "description": "Customer segments" }
  },
  "resourceTemplates": {
    "customer": { "route": "direct:readCustomer", "uriTemplate": "crm://customers/{id}",
                  "mimeType": "application/json", "description": "A customer" }
  },
  "prompts": {
    "summarize_account": { "route": "direct:summarizeAccount", "description": "Summarizes an account",
                           "arguments": [ { "name": "id", "required": true } ] }
  }
}
```

| Section | Notes |
|---|---|
| `tools` | `inputSchema` is an object schema, `outputSchema` any schema (draft 2020-12 unless `$schema` says otherwise); both are checked against their meta-schema. A string, integer or boolean property reached from the root through `properties` may carry `x-mcp-header`: the client then mirrors it in an `Mcp-Param-<name>` header. |
| `resources` | A fixed `uri`, listed by `resources/list`. |
| `resourceTemplates` | A `uriTemplate` (RFC 6570 level 1, `{var}` matches one path segment), listed by `resources/templates/list`. |
| `prompts` | `arguments` with `name`, `description`, `required`. |
| `auth` | Optional. Without it, no authentication (see [Authentication](#authentication)). |

Resources and resource templates take `route`, `description`, and optionally `title` and `mimeType`. A name used in
both sections is logged as a warning. Values are taken as written, with no property placeholders; settings that differ
per environment belong in `application.properties`.

**Startup checks.** The integration does not start when the file is missing or invalid, when a `route` has no
`direct:` consumer, or when the `auth` profile is incomplete or its identity provider cannot be reached. All problems
are reported at once:

```
mcp-server.json is invalid:
  $.tools.get_customer.route: no route consumes direct:getCustomer
```

**Reload.** When Camel reloads the routes (`camel run --dev`), the file is read again after the routes are back. An
invalid file is logged, and the previous definition keeps serving.

## How a call reaches a route

Each call sends a new exchange to the capability's route.

| Capability | Body in | Headers in |
|---|---|---|
| Tool | arguments `Map`, validated against `inputSchema` | `McpMethod` = `tools/call`, `McpName` |
| Resource | empty `Map` | `McpMethod` = `resources/read`, `McpName`, `McpUri` |
| Resource template | `Map` of the `uriTemplate` variables | `McpMethod` = `resources/read`, `McpName`, `McpUri` |
| Prompt | arguments `Map` (all values strings) | `McpMethod` = `prompts/get`, `McpName` |

With `auth`, the route also gets `McpSubject` (the token's `sub`), `McpScopes` (`List<String>`) and `McpClaims`
(`Map`). HTTP request headers never reach the route, except `breadcrumbId`. The exchange property `CamelCorrelationId`
is the HTTP exchange's id. Header names are constants in `com.talismanplatform.mcp.runtime.McpHeaders`.

The body the route returns becomes the result.

**Tool**
- A `Map`, `List` or POJO becomes `structuredContent`, plus the same JSON as a text item.
- A String, bytes or stream becomes one text item. It is parsed as JSON instead when the route sets
  `Content-Type: application/json` or the tool has an `outputSchema`.
- An exception, a result that does not match `outputSchema`, or arguments that fail `inputSchema` give
  `isError: true` with the message, so the model can correct the call. Stack traces go to the log only.

**Resource and resource template**
- The content type is `mimeType`, else the route's `Content-Type`, else none.
- A String body or a text type (`text/*`, JSON, XML, YAML) gives `text`; `Map` and POJO bodies are written as JSON.
  Anything else is returned as `blob` (base64).
- When a URI matches both a resource and a template, the resource wins.
- An exception gives JSON-RPC error -32603.

**Prompt**
- A String gives one user message.
- A `List` gives the messages: each a String (user) or a `{role: user|assistant, content}` map, where `content` is a
  String or an MCP content block.

## Authentication

With `auth`, every request needs `Authorization: Bearer <token>`. The token is validated by the camel-oauth
**named profile** given in `auth.oauthProfile`, configured in `application.properties`. These are the same profiles
platform-http's `oauthProfile` uses; the unnamed `camel.oauth.*` profile is not used.

```properties
camel.oauth.mcp.base-uri=https://idp.example.com/realms/acme
camel.oauth.mcp.expected-audience=crm-mcp
```

Property placeholders work as usual, for example `camel.oauth.mcp.base-uri={{env:OIDC_ISSUER}}`.

- **Profile keys.** `base-uri` (the issuer; its `/.well-known/openid-configuration` gives the JWKS) or
  `expected-issuer` with `jwks-endpoint`, and `expected-audience` (unless `allow-missing-audience=true`). Optional:
  `clock-skew-seconds`, `allowed-jws-algorithms`, `expected-token-type`, introspection (`introspection-endpoint`,
  `client-id`, `client-secret`), and `allow-insecure-http=true` for a plain-HTTP identity provider (development only).
- **Checks.** camel-oauth verifies the signature (JWKS, refreshed on an unknown `kid`), `iss`, `aud`, `exp`/`nbf` and
  the algorithm. The profile is checked when the context starts.
- **Scopes.** Read from the `scope` and `scp` claims. A token must have every scope in `auth.scopes`. Lists are not
  filtered by scope; a route that needs a finer check reads the `McpScopes` header.

| Situation | Response |
|---|---|
| No token, or an invalid one | 401 with `WWW-Authenticate: Bearer resource_metadata="…", scope="…"` (plus `error="invalid_token"` and an `error_description` when a token was sent) |
| A token without every scope in `auth.scopes` | 403 with `error="insufficient_scope"` |
| The identity provider cannot be reached | 503 |

Protected resource metadata (RFC 9728) is served on `GET /.well-known/oauth-protected-resource` (and `…/mcp`).
`authorization_servers` is the profile's issuer. The resource URL follows `X-Forwarded-Proto` and `X-Forwarded-Host`.

## Protocol details

- `POST /mcp`: one JSON-RPC request per POST, one JSON response. Notifications get 202; other HTTP methods 405.
- Methods: `server/discover`, `tools/list`, `tools/call`, `resources/list`, `resources/templates/list`,
  `resources/read`, `prompts/list`, `prompts/get`. `initialize` gets 404 with -32601 and the supported versions in
  `error.data`.
- Every request needs `params._meta` with `io.modelcontextprotocol/protocolVersion` and
  `io.modelcontextprotocol/clientCapabilities` (else 400, -32602).
- Every request needs the headers `MCP-Protocol-Version` and `Mcp-Method`, plus `Mcp-Name` on `tools/call`,
  `resources/read` and `prompts/get`, and `Mcp-Param-*` for `x-mcp-header` arguments. A missing header or one that
  does not match the body gives 400 with -32020 (HeaderMismatch). `=?base64?…?=` values are decoded.
- A protocol version other than 2026-07-28 gives 400 with -32022.
- Results carry `resultType: "complete"` and `_meta["io.modelcontextprotocol/serverInfo"]`. Lists, discover and
  reads carry `ttlMs: 0` and `cacheScope` (`private` with auth, otherwise `public`).
- A request with an `Origin` header (a browser) is accepted only from a loopback origin; anything else gets 403. This
  guards against DNS rebinding. MCP clients call from a process, which sends no `Origin`.

## Building from source

```bash
mvn verify                          # compile, license header check, tests
mvn verify -Dmcp.conformance=true   # plus the MCP conformance suite (needs Node.js and network)
```

## Contributing

Issues and pull requests are welcome on [GitHub](https://github.com/talismanplatform/talisman-mcp-runtime/issues).
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). Copyright 2026 Talisman Cloud Inc. See [NOTICE](NOTICE).
