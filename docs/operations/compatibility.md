# Compatibility

The starters ship the matching third-party runtime:

| Starters | Spring Boot | Spring Cloud Gateway | springdoc | DGS | Java |
| --- | --- | --- | --- | --- | --- |
| `*-spring-boot4-starter` | 4.1.x | 5.0.x | 3.2.x | 12.x | 21+ (generated serde tiers on 25+) |

**Spring Boot 3 is no longer supported.** 0.18.x is the last release line with the
`*-spring-boot3-*` starters (Boot 3.5, Spring Cloud Gateway 4.3, springdoc 2.10, DGS 10.6).
Spring Boot 3.5 and Spring Framework 6.2 left open source support on 2026-06-30;
`release/0.18.x` takes security fixes on a best-effort basis until May 2027. To move, swap
`spring-boot3` for `spring-boot4` in the artifact ids and upgrade the application to Boot 4: the
`shiguchi.*` configuration is the same.

On Boot 4 with GraphQL, Boot's dependency management holds json-path too low for DGS 12. Set
`json-path.version` to `3.0.0`
([details](troubleshooting.md#noclassdeffounderror-comjaywayjsonpathspijsonjackson3jsonprovider)).

Pre-1.0: minor releases (`0.x` → `0.y`) may contain breaking changes; they are called out
in [CHANGELOG.md](../../CHANGELOG.md). Patch releases are always drop-in. Security fixes land on
the latest release line (see [SECURITY.md](../../SECURITY.md)).
