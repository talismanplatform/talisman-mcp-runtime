# AGENTS.md — talisman-mcp-runtime

The library that makes a Camel Main integration an MCP server (2026-07-28, stateless Streamable HTTP). What it does,
the exchange contract, configuration and build: `README.md`. Do not add a `CLAUDE.md`.

## License

This artifact is **open source (Apache-2.0) although its package is `com.talismanplatform`**. The package names the
owner (Talisman Cloud Inc., doing business as Talisman Platform), not the license; integrations and third parties
depend on it.

- Every Java file and `pom.xml` start with the Apache header naming Talisman Cloud Inc., the legal entity
  (`src/license/header.txt`). `mvn verify` fails without it (license-maven-plugin, validate phase);
  `mvn license:format` adds it.
- `LICENSE` is the unmodified Apache 2.0 text. `NOTICE` names Talisman Cloud Inc., doing business as Talisman
  Platform. Keep both at the root; the `release` profile ships them.
- Dependencies stay Apache-2.0-compatible (Camel, Jackson, networknt json-schema-validator, nimbus via camel-oauth).
  Never depend on talisman-server or any proprietary Talisman code.

## Rules

- **Integrations run on Camel Main**, never Quarkus or Spring Boot. Say "Camel Main".
- Camel 4.22.x, Java 25 bytecode (`maven.compiler.release`). One version for the library and Talisman (26.16.x).
- No resources under `camel/` in the JAR: a dependency's `camel/` folder breaks route loading of the integration
  (`classpath:camel/*`).
- `mcp-server.schema.json` at the JAR root is the published format. Other tools (the MCP designer) validate against
  it. A change to it is a format change: keep it backward compatible, and keep `McpServerLoader` in step.
- Errors in `mcp-server.json` are reported all at once, each with its JSON path (`$.tools.x.route: …`).
- Protocol behaviour follows the MCP 2026-07-28 spec. Where it is ambiguous, the conformance suite decides
  (`McpConformanceTest`).
- Secrets never go into files: deploy credentials live in the user's `~/.m2/settings.xml`.

## Tests

`mvn verify` runs:

- `McpServerLoaderTest`, `UriTemplateTest`: the file format and its checks.
- `McpEndpointTest`: methods and HTTP binding, over HTTP against a Camel Main server.
- `McpAuthTest`: tokens against a stub OIDC issuer.
- `McpStartupTest`: an invalid definition stops startup.

`McpConformanceTest` runs the official suite only with `-Dmcp.conformance=true` (Node.js, network). Trim the output:
`mvn -q verify 2>&1 | tail -n 50`.
