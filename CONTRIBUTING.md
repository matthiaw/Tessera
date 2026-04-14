# Contributing to Tessera

Thanks for your interest in Tessera. This guide gives you the minimum you need to bootstrap a productive development loop.

## License

Tessera is licensed under the **Apache License, Version 2.0**. By submitting a pull request, you agree that your contribution is licensed under Apache 2.0 and that you have the right to make it. Submitting a PR is treated as acknowledgement of the Developer Certificate of Origin (DCO).

## Build

A fresh clone must build green with the committed Maven Wrapper. CI runs the same command.

```bash
./mvnw -B verify
```

If `./mvnw -B verify` fails on a clean checkout, that is a bug — please open an issue.

## Code style

Java code is formatted with **Palantir Java Format**, enforced by the Spotless Maven plugin at the `validate` phase. Format drift fails the build.

```bash
./mvnw spotless:apply   # auto-fix formatting
./mvnw spotless:check   # what CI runs
```

## License headers

Every `.java` file must carry the Apache 2.0 header from `.mvn/license-header.txt`. The `license-maven-plugin` (mycila) verifies this at `validate`.

```bash
./mvnw license:format   # auto-apply the header
./mvnw license:check    # what CI runs
```

## Tests

- **Unit tests** live in `src/test/java` and follow the `*Test.java` naming convention. Run with `./mvnw test`.
- **Integration tests** live in `src/test/java` and follow the `*IT.java` naming convention. They use Testcontainers for a real PostgreSQL + Apache AGE instance. Run with `./mvnw verify`.

## Commits

Conventional commit messages are encouraged (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`). Please do **not** add `Co-Authored-By` lines — Tessera's commit history attributes work via `Author:` only.

## Module direction

Tessera is a Maven multi-module project with strictly upward dependencies:

```
fabric-app
  ├── fabric-projections ── fabric-rules ──┐
  ├── fabric-connectors ───────────────────┤
  └── fabric-rules ────────────────────────┴── fabric-core
```

`fabric-core` sits at the bottom of the dependency graph and depends on **no** other Tessera module. `fabric-connectors` must **not** depend on `fabric-projections`. ArchUnit and the Maven enforcer plugin (`banCircularDependencies`) will fail your build if you reverse the direction or introduce a cycle.

## GSD workflow

Repository work flows through the GSD command set (`.claude/commands/gsd-*`) per the project `CLAUDE.md`. Ad-hoc edits outside the GSD workflow require explicit maintainer permission. For small fixes use `/gsd-quick`; for planned phase work use `/gsd-execute-phase`.
