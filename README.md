# Talisman MCP Runtime

Makes a Camel Main integration an [MCP](https://modelcontextprotocol.io) server. Add the dependency, describe the
server in `mcp-server.json`, and implement each tool, resource and prompt as a `direct:` route. The runtime serves
MCP **2026-07-28** (stateless Streamable HTTP) on `/mcp` of Camel's platform-http server.

```xml
<dependency>
    <groupId>com.talismanplatform</groupId>
    <artifactId>talisman-mcp-runtime</artifactId>
    <version>26.16.0</version>
</dependency>
```

The dependency alone activates the runtime: it registers a Camel `ContextServicePlugin`
(`META-INF/services/org.apache.camel.spi.ContextServicePlugin`). It needs Camel 4.22.x and Java 17. The HTTP server
must be enabled (`camel.server.enabled=true`; `camel.server.port`, 8080 by default).

## `mcp-server.json`

Read from the classpath when the context starts. The JSON Schema is `mcp-server.schema.json` at the root of the JAR
(`$id` `https://talismanplatform.com/schemas/mcp-server.schema.json`).

```json
{
  "info": { "name": "crm", "version": "1.0.0", "title": "CRM", "instructions": "Look customers up by id." },
  "auth": { "issuer": "{{mcp.auth.issuer}}", "audience": "{{mcp.auth.audience}}", "scopes": ["crm"] },
  "tools": {
    "get_customer": {
      "route": "direct:getCustomer",
      "description": "Returns a customer",
      "inputSchema": { "type": "object", "properties": { "id": { "type": "string" } }, "required": ["id"] },
      "annotations": { "readOnlyHint": true },
      "scopes": ["crm.read"]
    }
  },
  "resources": {
    "customer": { "route": "direct:readCustomer", "uriTemplate": "crm://customers/{id}",
                  "mimeType": "application/json", "description": "A customer" }
  },
  "prompts": {
    "summarize_account": { "route": "direct:summarizeAccount", "description": "Summarizes an account",
                           "arguments": [ { "name": "id", "required": true } ] }
  }
}
```

- String values may hold Camel property placeholders (`{{key}}`, `{{key:default}}`), resolved before validation.
- Tools: `inputSchema` is an object schema, `outputSchema` any schema (draft 2020-12 unless `$schema` says
  otherwise); both are checked against their meta-schema. A string, integer or boolean property reached from the root
  through `properties` may carry `x-mcp-header`, which the client mirrors in an `Mcp-Param-<name>` header.
- Resources: either `uri` (a fixed resource) or `uriTemplate` (RFC 6570 level 1, `{var}` matches one path segment).
- `auth` absent: no authentication.

### Startup checks

The integration does not start when the file is missing or invalid, or when a `route` has no `direct:` consumer
in the context. Every problem is reported with its JSON path:

```
mcp-server.json is invalid:
  $.tools.get_customer.route: no route consumes direct:getCustomer
```

On a dev-mode reload (`ContextServicePlugin.onReload`) the file is read again. An invalid file is logged, and the
previous definition keeps serving.

## Exchange contract

Each call sends a new exchange to the capability's route.

| | Body in | Headers in |
|---|---|---|
| Tool | arguments `Map`, validated against `inputSchema` | `McpMethod` = `tools/call`, `McpName` |
| Resource | `Map` of the `uriTemplate` variables (empty for `uri`) | `McpMethod` = `resources/read`, `McpName`, `McpUri` |
| Prompt | arguments `Map` (all values strings) | `McpMethod` = `prompts/get`, `McpName` |

With `auth`, `McpSubject` (token `sub`), `McpScopes` (`List<String>`) and `McpClaims` (`Map`) are set too. HTTP
request headers never reach the route. Constants: `com.talismanplatform.mcp.runtime.McpHeaders`.

The body out becomes the result:

- **Tool.**
  - `Map`, `List` or POJO: `structuredContent`, plus the same JSON as a text content item.
  - String, bytes, stream: one text item. It is parsed as JSON instead when the route sets
    `Content-Type: application/json` or the tool has an `outputSchema`.
  - A result that does not match the `outputSchema`, or an exception, gives `isError: true` with the message (the
    stack trace goes to the log).
  - Arguments that fail `inputSchema` give `isError: true` too, so the model can correct the call.
- **Resource.**
  - A text `mimeType` (`text/*`, JSON, XML, YAML, …) gives `text`; `Map`/POJO bodies are written as JSON.
  - Otherwise `blob` (base64).
  - An exception gives JSON-RPC error -32603.
- **Prompt.**
  - A String gives one user message.
  - A `List` gives the messages: each a String (user) or a `{role: user|assistant, content}` map, where `content` is
    a String or an MCP content block.

## Protocol

- `POST /mcp`, one JSON-RPC request per POST, one JSON response.
- Notifications → 202. Other HTTP methods → 405.
- Methods:
  - `server/discover`
  - `tools/list`, `tools/call`
  - `resources/list`, `resources/templates/list`, `resources/read`
  - `prompts/list`, `prompts/get`
- `initialize` (removed in 2026-07-28) → 404 with -32601 and the supported versions in `error.data`.
- Every request needs:
  - `params._meta` with `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities`;
    missing → 400 with -32602.
  - The headers `MCP-Protocol-Version` and `Mcp-Method`, plus `Mcp-Name` on `tools/call`, `resources/read` and
    `prompts/get`, and `Mcp-Param-*` for `x-mcp-header` arguments. Missing or not matching the body → 400 with
    -32020 (HeaderMismatch). `=?base64?…?=` values are decoded.
- A version other than 2026-07-28 → 400 with -32022.
- Results carry `resultType: "complete"` and `_meta["io.modelcontextprotocol/serverInfo"]`. Lists, discover and
  reads carry `ttlMs: 0`, and `cacheScope` `private` with auth, otherwise `public`.
- A request with an `Origin` header (a browser) is accepted only from a loopback origin, anything else → 403. This
  guards against DNS rebinding. MCP clients call from a process (a server, an IDE's extension host), which sends no
  `Origin`.

## Authentication

With `auth`, every request needs `Authorization: Bearer <token>`. Camel's token validation (camel-oauth) checks:

- the signature, against the issuer's JWKS (found through `<issuer>/.well-known/openid-configuration`, cached);
- `iss`, `aud`, `exp` and `nbf`.

Scopes are read from the `scope` and `scp` claims. A plain-http issuer is accepted (a development setup).

- No token or an invalid one → 401 with `WWW-Authenticate: Bearer resource_metadata="…", scope="…"`
  (`error="invalid_token"` when a token was sent).
- Missing `auth.scopes` → 403 with `error="insufficient_scope"`.
- A capability's `scopes` come on top of `auth.scopes`. The lists show only the capabilities the caller's scopes
  allow; a call without them → 403.
- Protected resource metadata (RFC 9728): `GET /.well-known/oauth-protected-resource` (and `…/mcp`). The resource
  URL follows `X-Forwarded-Proto` and `X-Forwarded-Host`.

## Build

```bash
mvn verify                                   # compile, license header check, tests
mvn verify -Dmcp.conformance=true            # plus the MCP conformance suite (needs Node.js and network)
mvn license:format                           # add missing license headers
```

The conformance run uses `@modelcontextprotocol/conformance` at protocol 2026-07-28, against a fixture server
(`src/test/resources/mcp/conformance.json`). It runs every scenario for what the runtime serves.
`conformance-baseline.yml` lists the checks the suite cannot run against it.

Deploy to a Reposilite (`reposilite.url`, default `http://reposilite:8080/releases`):

```bash
REPOSILITE_USER=… REPOSILITE_TOKEN=… mvn -s .mvn/reposilite-settings.xml -Preposilite deploy
```

`-s` replaces the user settings. Add `-Dmaven.repo.local=…` when those set the local repository.

Releases to Maven Central use the `release` profile (sources, javadoc, GPG signature, Central Portal publishing):
`mvn -Prelease deploy`.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`. Copyright 2026 Talisman Platform.
