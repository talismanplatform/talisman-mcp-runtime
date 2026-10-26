# Contributing

Thank you for helping improve Talisman MCP Runtime.

## Issues

Report bugs and propose features in [GitHub Issues](https://github.com/talismanplatform/talisman-mcp-runtime/issues).
For a bug, include the runtime, Camel and Java versions, the `mcp-server.json` (or a minimal one that reproduces it),
the request, and the log.

## Pull requests

1. Open an issue first for anything larger than a small fix, so the approach can be agreed.
2. Keep a pull request to one change, with a test that covers it.
3. Run the build, including the conformance suite (needs Node.js and network):
   ```bash
   mvn verify -Dmcp.conformance=true
   ```
4. Every Java file and `pom.xml` starts with the Apache License header. The build fails without it;
   `mvn license:format` adds it.

Rules the code follows:

- Behaviour follows the MCP 2026-07-28 specification. Where the specification is ambiguous, the official conformance
  suite decides (`McpConformanceTest`). `src/test/resources/mcp/conformance-baseline.yml` lists the checks the suite
  cannot run against this runtime.
- `mcp-server.schema.json` is a published format that other tools validate against. Changes must stay backward
  compatible, and `McpServerLoader` must change with it.
- Dependencies must be Apache-2.0-compatible.

By submitting a contribution you agree that it is licensed under the [Apache License 2.0](LICENSE), as stated in its
section 5.

## Releasing (maintainers)

Versions follow Talisman Platform releases (`26.16.x`). A version published to Maven Central is permanent: a broken
release is fixed by a new version.

1. Set the new version in `pom.xml` and in the README (the Maven and `camel run` examples, and the compatibility
   table for a new minor version). Commit and push.
2. Make sure the build on `main` is green, including the conformance job.
3. Tag and push: `git tag 26.16.0 && git push origin 26.16.0`.
4. The Release workflow checks that the tag matches `pom.xml` and the README, runs the tests and the conformance
   suite, then signs the artifacts and publishes them to Maven Central (`mvn -Prelease deploy`). A maintainer
   approves it in the `release` environment.
5. Create the GitHub release with notes and the Maven coordinates.
