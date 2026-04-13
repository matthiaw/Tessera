# Stack Research

**Domain:** Graph-based, protocol-agnostic integration layer / data fabric (Java/Spring ecosystem)
**Researched:** 2026-04-13
**Confidence:** MEDIUM-HIGH overall. HIGH for Spring Boot / Jena / Debezium / Testcontainers / Vault. MEDIUM for Apache AGE (official releases are all `-rc0` tagged). MEDIUM for Spring AI MCP Server (GA exists but still young and evolving fast across 1.0.x → 1.1.x → 2.0.x-M tracks).

---

## Executive Verdict on Locked Decisions

| Locked Decision | Verdict | Evidence |
|---|---|---|
| Java 21 + Spring Boot 3.x | CONFIRM | Spring Boot 3.5.13 released 2026-03-26, actively supported, Java 21 baseline works. 3.4.x is EOL open-source support as of 2026. |
| PostgreSQL 16 + Apache AGE | CONFIRM WITH CAVEAT | AGE 1.6.0 is the highest AGE release for PG16 (released Sept/Nov 2024). AGE 1.7.0 only exists for PG17/PG18. All releases tagged `-rc0`. See risks below. |
| Apache Jena SHACL | CONFIRM | Jena 5.x is current, Java 21 compatible, actively maintained (Feb 2026 release). |
| Spring AI MCP Server | CONFIRM WITH CAVEAT | Spring AI 1.0 GA shipped 2025-05; `spring-ai-starter-mcp-server` is real and on Central. But ecosystem is churning (1.0.x stable, 1.1.x current, 2.0.0-Mx in flight). Expect breaking changes between minor versions. |
| SpringDoc OpenAPI | CONFIRM | springdoc-openapi-starter-webmvc-ui 2.8.x is the Spring Boot 3.x line. |
| Custom rule engine (MVP); Drools later | CONFIRM | Sound call — Drools brings KIE/Workbench baggage that is overkill for priority-based chain-of-responsibility. |
| Maven multi-module | CONFIRM | Matches circlead conventions; Gradle offers no meaningful advantage for this project shape. |
| In-process events (MVP); Debezium/Kafka later | CONFIRM | `ApplicationEventPublisher` is sufficient for MVP. Debezium 3.4+ supports PG16/17/18 cleanly via logical replication slots. |
| HashiCorp Vault | CONFIRM | Spring Cloud Vault 4.x is the Spring Boot 3.x line and actively maintained. |

**No decision needs to be re-litigated.** The caveats on AGE and Spring AI MCP are version/maturity risks, not architectural errors.

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|---|---|---|---|
| Java (OpenJDK/Corretto) | **21** (LTS) | Runtime | LTS, virtual threads (connectors!), pattern matching, records for DTOs. Already installed on dev machine. Spring Boot 3.5 baseline-compatible. |
| Spring Boot | **3.5.13** (latest patch of 3.5.x line as of 2026-03-26) | App framework | 3.5.x is the current supported line. 3.4.x reached open-source EOL. 4.0 is in flight but not GA-stable for new greenfield work yet. Pin to 3.5.13; upgrade patches freely. |
| PostgreSQL | **16** (16.6+) | Primary store | Matches locked decision. AGE 1.6.0 targets PG16 specifically. PG16 is mature, well-supported by Debezium/Testcontainers/tooling. Do NOT jump to PG17 unless you also jump to AGE 1.7.0 (see risk section). |
| Apache AGE | **1.6.0 (PG16 branch)** — tag `PG16/v1.6.0-rc0` | Graph extension in Postgres | The only AGE release line matching PG16. Cypher-in-Postgres with ACID. **Risk:** Official releases carry `-rc0` suffix — Apache's release cadence treats these as production-usable but the naming is unsettling. Pin exactly; do not drift. |
| PostgreSQL JDBC driver | **42.7.5** (or newest 42.7.x) | DB driver | Standard `org.postgresql:postgresql`. Works with AGE — AGE is server-side. You do NOT need the AGE JDBC driver unless you want AGType parsing; for most use cases a bog-standard pgJDBC plus `SET search_path = ag_catalog, public;` is sufficient. |
| Apache Jena | **5.2.0** (or latest 5.x; Feb 2026) | SHACL validation, RDF tooling | `jena-shacl` module. Jena 5.x requires Java 17+, fully Java 21 compatible. Active 2026 releases. Industry-standard SHACL implementation. |
| Maven | **3.9.x** | Build | Already installed. Multi-module parent-POM layout. |

### Spring Ecosystem Libraries

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| `spring-boot-starter-web` | 3.5.13 | REST endpoints (Tomcat + Spring MVC) | MVP REST projection. Consider WebFlux only if connector count explodes past ~100; otherwise virtual threads on MVC solve the blocking concern. |
| `spring-boot-starter-data-jpa` | 3.5.13 | Relational metadata, schema registry, event log | The event log and schema registry are relational, not graph. JPA fits. |
| `spring-boot-starter-jdbc` | 3.5.13 | Raw Cypher queries via `JdbcTemplate` / `NamedParameterJdbcTemplate` | AGE queries go through plain JDBC — there is no Spring Data AGE module. Wrap with a thin `GraphQueryTemplate`. |
| `spring-boot-starter-validation` | 3.5.13 | Bean Validation on DTOs | Standard. |
| `spring-boot-starter-actuator` | 3.5.13 | Health, metrics, readiness | Required for Docker Compose healthchecks on IONOS. |
| `spring-boot-starter-security` | 3.5.13 | AuthN/AuthZ | OAuth2 resource server for REST projection; method security for MCP tools. |
| `spring-ai-starter-mcp-server-webmvc` | **1.0.5** (stable 1.0.x) or **1.1.x** if you want SSE streaming improvements | MCP projection | Groupid `org.springframework.ai`. Use the `-webmvc` variant to match Spring MVC. **Pin strictly** — Spring AI minor versions have broken APIs during 2025-2026. |
| `springdoc-openapi-starter-webmvc-ui` | **2.8.x** (latest in the Spring Boot 3.5 line) | OpenAPI 3 + Swagger UI | Automatic OpenAPI generation from dynamically-registered REST controllers. Make sure dynamic controller registration happens before Springdoc introspection (lifecycle gotcha). |
| `spring-cloud-starter-vault-config` | **4.2.x** (Spring Cloud 2024.0.x / Moorgate) | HashiCorp Vault secrets | Use Spring Boot Config Data API (`spring.config.import=vault://...`), NOT the deprecated bootstrap context. AppRole auth for Docker Compose deploy. |

**Version lock file for the Spring side:**

```xml
<properties>
    <java.version>21</java.version>
    <spring-boot.version>3.5.13</spring-boot.version>
    <spring-cloud.version>2024.0.1</spring-cloud.version> <!-- verify latest patch -->
    <spring-ai.version>1.0.5</spring-ai.version>         <!-- pin, do not drift -->
    <springdoc.version>2.8.6</springdoc.version>
    <jena.version>5.2.0</jena.version>
    <postgresql.version>42.7.5</postgresql.version>
    <testcontainers.version>1.20.4</testcontainers.version>
</properties>
```

### Graph / Validation / Rules Libraries

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| `org.apache.jena:jena-shacl` | 5.2.0 | SHACL validation engine | Validate entity mutations against per-`model_id` shape graphs before persisting to AGE. |
| `org.apache.jena:jena-arq` | 5.2.0 | SPARQL + RDF model API | Transitively pulled in by jena-shacl. Use `Model`/`Graph` APIs to build shapes. |
| (Custom) `tessera-rule-engine` module | internal | Chain-of-responsibility rule engine | Per ADR-3. Do not pull in Drools, Easy Rules, or RuleBook yet — they all bring more than they give at MVP scope. |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8+ | In-memory cache for compiled SHACL shapes, projection metadata | Re-compiling Jena shape graphs per request is expensive; cache them keyed by `(model_id, schema_version)`. |

### Connector / Integration Libraries (MVP)

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| `spring-boot-starter-webflux` OR `java.net.http.HttpClient` | 3.5.13 / JDK 21 | Outbound HTTP for polling REST connector | JDK 21 `HttpClient` + virtual threads is sufficient and avoids a second web stack. Only pull WebFlux if reactive streaming is required. |
| `com.fasterxml.jackson.core:*` | Brought by Spring Boot BOM | JSON parsing for connector responses | Default Jackson works. |
| `org.quartz-scheduler:quartz` OR Spring `@Scheduled` | — | Connector polling schedule | For MVP, `@Scheduled` + `ShedLock` if you ever run >1 instance. Quartz is overkill until you need cron persistence. |
| `net.javacrumbs.shedlock:shedlock-spring` | 5.16.0+ | Distributed lock on Postgres for scheduled jobs | Even on single-instance IONOS, add this day one — it is cheap insurance and prevents double-polling after restarts. |

### Debezium / Kafka (Phase 2, not MVP)

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| `io.debezium:debezium-connector-postgres` | **3.4.0.Final** (Dec 2025) | CDC from Postgres (event log table) to Kafka | Phase 2. Uses logical replication + `pgoutput` plugin (PG16 default). |
| Kafka | 3.9.x (matches your `~/Programmming/Services/Kafka 3.9` install) | Event fan-out | Phase 2. |
| `io.debezium:debezium-testing-testcontainers` | 3.4.0.Final | Debezium integration tests | Phase 2. |

**PG16 + Debezium 3.4:** confirmed working. Debezium 3.0.1+ added PG17, 3.4 added PG18. PG16 has been supported since Debezium 2.x. No compatibility action needed.

### Testing Libraries

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| `spring-boot-starter-test` | 3.5.13 | JUnit 5, Mockito, AssertJ, Spring test | Unit + slice tests. |
| `org.testcontainers:postgresql` | 1.20.4 | Postgres container for integration tests | **Use with a custom AGE image** — see Version Compatibility section. |
| `org.testcontainers:junit-jupiter` | 1.20.4 | JUnit 5 integration | Standard. |
| `org.testcontainers:testcontainers` | 1.20.4 | Base Testcontainers | Standard. |
| `com.tngtech.archunit:archunit-junit5` | 1.3.x | Architecture tests to enforce module boundaries | Tessera is multi-module and hexagonal — ArchUnit prevents connector code from reaching into the graph module directly. Worth it from day one. |
| `io.rest-assured:rest-assured` | 5.5.x | Integration tests for REST projection | Cleaner than MockMvc for end-to-end projection tests. |
| `org.wiremock:wiremock-standalone` | 3.10.x | Mock upstream REST APIs for connector tests | Required for testing the REST polling connector without hitting real systems. |

### Build / Dev Tools

| Tool | Purpose | Notes |
|---|---|---|
| Maven Wrapper (`mvnw`) | Reproducible builds | Commit `.mvn/wrapper/` so contributors get the right Maven version. |
| `maven-compiler-plugin` 3.13.0+ | Java 21 compilation | Set `<release>21</release>`. Add `-parameters` for Spring method reflection. |
| `maven-surefire-plugin` 3.5.x | Unit tests | JUnit 5 works out of box. Configure `<argLine>` for Mockito agent on Java 21+. |
| `maven-failsafe-plugin` 3.5.x | Integration tests (`*IT.java`) | Run after surefire; separate from unit tests so CI can parallelise. |
| `jacoco-maven-plugin` 0.8.12+ | Coverage | 0.8.11 had Java 21 issues; 0.8.12+ is fine. |
| `spotless-maven-plugin` 2.44.x | Code formatting | Google Java Format or Palantir. Fail the build on format drift. |
| `spotbugs-maven-plugin` 4.8.x | Static analysis | Optional but cheap; good for OSS contributor confidence. |
| `license-maven-plugin` (mycila) | Apache 2.0 license headers | Required for an Apache-licensed OSS repo. |
| Flyway | 10.x (brought by Spring Boot BOM) | Postgres migrations including `CREATE EXTENSION age;` | Do NOT use Liquibase — Flyway's plain-SQL approach maps better to AGE's procedural catalog setup. |
| Docker Compose v2 | Local dev + IONOS deploy | Single compose file: `postgres-age`, `tessera`, `vault`. |

---

## Installation (Representative POM Snippets)

**Parent POM (`pom.xml`):**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.13</version>
</parent>

<properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <spring-cloud.version>2024.0.1</spring-cloud.version>
    <spring-ai.version>1.0.5</spring-ai.version>
    <jena.version>5.2.0</jena.version>
    <testcontainers.version>1.20.4</testcontainers.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>${testcontainers.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Graph module (`tessera-graph/pom.xml`):**

```xml
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
    <dependency><groupId>org.apache.jena</groupId><artifactId>jena-shacl</artifactId><version>${jena.version}</version></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
</dependencies>
```

**MCP projection module (`tessera-projection-mcp/pom.xml`):**

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
</dependencies>
```

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|---|---|---|
| PostgreSQL + Apache AGE | Neo4j (primary) | If graph traversal depth consistently exceeds 5 hops on multi-million-node graphs AND relational projections are a secondary concern. Not Tessera's case. Keep as Phase-later read-replica if performance demands it (ADR-1 already documents this path). |
| PostgreSQL + Apache AGE | JanusGraph / ArangoDB | Never for this project. Both drag in a second DB engine and lose ACID-across-graph-and-relational. |
| Apache Jena SHACL | TopQuadrant SHACL API (`org.topbraid:shacl`) | TopQuadrant is the reference implementation historically and has better SHACL-AF (Advanced Features) support. Switch only if you need `sh:rule` / `sh:SPARQLRule`. Jena SHACL is sufficient for Core + SPARQL constraints, which is 95% of the use case. |
| Custom rule engine | Drools | Only when you need CEP, decision tables, or non-developers authoring rules via Workbench. Per ADR-3, defer. |
| Custom rule engine | Easy Rules / RuleBook | Not worth the dependency. Chain-of-responsibility is ~100 lines of Java. |
| Spring AI MCP Server | Anthropic Java MCP SDK (`io.modelcontextprotocol:sdk-java`) | If Spring AI MCP starter proves too Spring-opinionated or breaks on a minor upgrade. The raw SDK is more work but under your control. Keep in back pocket. |
| Spring MVC + virtual threads | Spring WebFlux | WebFlux only if you hit 10k+ concurrent connector polls. Virtual threads on MVC handle Tessera's expected load comfortably and keep debugging sane. |
| Flyway | Liquibase | Liquibase's XML/YAML abstraction fights you when you need `CREATE EXTENSION age; LOAD 'age'; SET search_path = ag_catalog, public;` and AGE's procedural catalog setup. Flyway's plain SQL wins. |
| Spring Cloud Vault | Direct Vault Java driver (`com.bettercloud:vault-java-driver`) | Only if you do not want Spring Cloud on the classpath. Spring Cloud Vault's Config Data API integration is too convenient to give up. |
| Testcontainers | Embedded Postgres (`io.zonky.test:embedded-postgres`) | Never — zonky embedded Postgres does NOT support loadable extensions like AGE. Testcontainers with a real Docker image is the only viable path. |
| Maven multi-module | Gradle | Gradle offers faster builds and Kotlin DSL, but circlead uses Maven and Tessera's consistency with it is worth more than build speed. Do not split the ecosystem. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|---|---|---|
| **Spring Boot 3.4.x** | Open-source support ended — 3.4.13 was the final release. Starting new greenfield on 3.4 burns a migration within 3 months. | Spring Boot 3.5.13 |
| **Spring Boot 4.0 / Spring Framework 7.x** | Still stabilizing as of April 2026 for greenfield production work; too many Spring AI / Spring Cloud integrations have not caught up. | Spring Boot 3.5.x; plan upgrade to 4.0 in milestone 2+ |
| **Apache AGE 1.5.0 on PG16** | Superseded by 1.6.0 for PG16. | AGE 1.6.0 PG16 branch |
| **AGE 1.7.0** (on PG17/PG18) for MVP | Would force you off PG16 and away from the most battle-tested pairing. Your locked decision (PG16) is correct. | AGE 1.6.0 PG16 |
| **Spring AI 2.0.0-Mx milestones** | Milestone quality; API churn. | Spring AI 1.0.x stable line (1.0.5 as of March 2026) or carefully 1.1.x stable |
| **`bootstrap.yml` for Vault config** | Deprecated since Spring Cloud Vault 3.0 / Spring Boot 2.4. | `spring.config.import=vault://` (Config Data API) |
| **Neo4j Java driver / OGM** | Not needed — you are not using Neo4j. Do not pull it in "just in case"; it confuses contributors about the architecture. | Plain JDBC to AGE via `JdbcTemplate` |
| **Spring Data Neo4j** | Same reason. Actively harmful signal. | — |
| **Drools (in MVP)** | Brings KIE / drools-compiler / drools-core / drools-mvel. >20 MB of dependencies for priority-based chains you can write in 100 lines. (ADR-3) | Custom chain-of-responsibility rule engine |
| **Liquibase for this project** | Struggles with AGE catalog setup + `LOAD 'age'` directives. Plain SQL migrations are cleaner. | Flyway |
| **Zonky embedded Postgres for tests** | Cannot load the AGE extension. | Testcontainers with `apache/age:*` image |
| **Guava `EventBus`** | Out of process boundary, no async ordering guarantees, dead project-adjacent. | Spring `ApplicationEventPublisher` (MVP) → Debezium/Kafka (Phase 2) |
| **GraphQL Java / DGS in MVP** | GraphQL projection is Phase 4 per PROJECT.md. Pulling it in early creates premature schema coupling. | REST + MCP for MVP |
| **`org.postgresql:r2dbc-postgresql`** | R2DBC does not support AGE's Cypher dialect cleanly and virtual threads make reactive unnecessary for Tessera's shape. | Blocking `pgJDBC` + virtual threads |

---

## Stack Patterns by Variant

**If you stay strictly on locked PG16 (recommended for MVP):**
- Use AGE `PG16/v1.6.0-rc0`
- Use pgoutput logical replication plugin (built-in since PG10)
- Debezium Phase 2 works without additional plugins

**If you later jump to PG17 (Phase 3+):**
- Switch to AGE `PG17/v1.7.0-rc0`
- Gain PG17 failover-aware replication slots (big deal for Debezium HA)
- Expect a full Flyway migration pass and a reimport of graph data — AGE has no in-place PG-major upgrade story

**If Spring AI MCP Server breaks on a minor upgrade:**
- Fall back to raw `io.modelcontextprotocol:sdk-java` (Anthropic's official Java SDK)
- You lose Spring Boot auto-configuration but gain API stability
- Keep the MCP projection module interface-isolated so this swap is feasible without touching the graph core

**If connector count explodes past ~50 polling connectors:**
- Do NOT switch to WebFlux
- Instead: enable Java 21 virtual threads (`spring.threads.virtual.enabled=true`) and scale Tomcat's connector with virtual-thread-backed executor

---

## Version Compatibility

| Package A | Compatible With | Notes |
|---|---|---|
| Spring Boot 3.5.13 | Java 21 / 23 / 24 | Java 21 is the sweet spot (LTS + virtual threads). Java 17 still technically works but loses virtual threads. |
| Spring Boot 3.5.13 | Spring Cloud 2024.0.x (Moorgate) | Confirmed train. Do not pair with older Spring Cloud 2023.0.x. |
| Spring Boot 3.5.13 | Spring AI 1.0.x / 1.1.x | 1.0.5 is the conservative pick. Test 1.1.x in a feature branch before committing. |
| Apache AGE 1.6.0 | **PostgreSQL 16.x only** | Do NOT run AGE 1.6.0 on PG17. There is no cross-version compatibility. Each AGE version is hard-bound to a PG major. |
| Apache AGE 1.7.0 | PostgreSQL 17 or 18 | Not applicable to MVP. |
| Apache Jena 5.x | Java 17+ (Java 21 OK) | No known issues on Java 21. |
| Debezium 3.4.0.Final | PostgreSQL 14, 15, 16, 17, 18 | PG13 dropped in 3.4. PG16 fully supported. |
| Testcontainers 1.20.x | Docker 20.10+ | Ensure `apache/age` Docker image is pinned to a specific digest for reproducible tests. |
| Spring Cloud Vault 4.x | Spring Boot 3.x, Vault 1.13+ | Use Config Data API, not bootstrap. |
| PostgreSQL JDBC 42.7.x | PostgreSQL 16/17/18 | Standard. |
| Flyway 10.x | PostgreSQL 16 | Use `flyway-database-postgresql` separate module (Flyway 10 split drivers out). |

### The Testcontainers-AGE recipe

There is no official Testcontainers AGE module. Use the official `apache/age` Docker image as a custom substitute for `postgres`:

```java
static final DockerImageName AGE_IMAGE = DockerImageName
    .parse("apache/age:PG16_latest")                 // pin to a digest in production
    .asCompatibleSubstituteFor("postgres");

@Container
static final PostgreSQLContainer<?> postgres =
    new PostgreSQLContainer<>(AGE_IMAGE)
        .withDatabaseName("tessera")
        .withUsername("tessera")
        .withPassword("tessera");
```

Then in a Flyway baseline migration:

```sql
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT create_graph('tessera_main');
```

**Pitfall:** `LOAD 'age'` and `SET search_path` are session-local. Every JDBC connection needs them. Use a HikariCP `connectionInitSql`:

```yaml
spring.datasource.hikari.connection-init-sql: "LOAD 'age'; SET search_path = ag_catalog, '$user', public;"
```

---

## Key Risks (Surface These Loudly)

1. **Apache AGE `-rc0` tagging is alarming but not blocking.** AGE's Apache release process tags community-voted releases with `-rc0` suffix even when they are the final. They are used in production, but you should:
   - Pin to a specific commit SHA or image digest
   - Subscribe to `dev@age.apache.org`
   - Have an escape plan (Neo4j read-replica per ADR-1)
   - Budget one full day in MVP to stress-test transaction semantics with concurrent writes, because community bug reports exist around long-running Cypher transactions

2. **Spring AI MCP Server churn.** Spring AI went 1.0 GA in May 2025 but has iterated through 1.0.x → 1.1.x → 2.0.0-Mx in under a year. Minor version upgrades have broken APIs. Mitigations:
   - Keep the MCP projection in its own module behind an interface
   - Pin Spring AI BOM and upgrade deliberately, not automatically
   - Track `spring-projects/spring-ai` releases before any `spring-ai.version` bump

3. **SpringDoc + dynamic controller registration.** Tessera generates REST controllers from the schema at startup. SpringDoc introspects controllers at startup too. Ensure the projection engine registers all dynamic endpoints in a `@Bean`-level initializer that runs BEFORE SpringDoc scans, or SpringDoc will miss them entirely. This is a known issue with any dynamic-endpoint Spring application.

4. **Debezium replication slot lifecycle.** When you add Debezium in Phase 2, the replication slot will retain WAL forever if Debezium stops. This can fill your IONOS disk. Add monitoring from day one of Phase 2 and set `max_slot_wal_keep_size` in `postgresql.conf`.

5. **Jena SHACL and graph-store backing.** Jena SHACL expects an RDF Graph in memory. For large shape graphs across many tenants, cache compiled `Shapes` objects via Caffeine keyed by `(model_id, schema_version)`. Do not recompile per request.

---

## Sources

- [Apache AGE GitHub releases](https://github.com/apache/age/releases) — confirmed PG16 → 1.6.0, PG17 → 1.7.0, all tagged `-rc0`. HIGH confidence.
- [Spring Boot 3.5.13 release blog](https://spring.io/blog/2026/03/19/spring-boot-3-5-12-available-now/) and [3.5.13 blog](https://spring.io/blog/2026/03/26/spring-boot-3-5-13-available-now/) — latest 3.5.x patch. HIGH confidence.
- [Spring Boot endoflife.date](https://endoflife.date/spring-boot) — 3.4.x EOL, 3.5.x active. HIGH confidence.
- [Spring AI 1.0 GA blog](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/) and [Spring AI 2.0.0-M3 / 1.1.3 / 1.0.4 blog (2026-03-17)](https://spring.io/blog/2026/03/17/spring-ai-2-0-0-M3-and-1-1-3-and-1-0-4-available/) — version lines confirmed. MEDIUM confidence on exact patch numbers; HIGH on maturity status.
- [Spring AI MCP Server Boot Starter docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) — HIGH confidence on starter coordinates.
- [Apache Jena download page](https://jena.apache.org/download/) and [jena-shacl docs](https://jena.apache.org/documentation/shacl/) — HIGH confidence on Java 21 compat and active maintenance.
- [Debezium 3.4.0.Final release blog (2025-12-16)](https://debezium.io/blog/2025/12/16/debezium-3-4-final-released/) — HIGH confidence on PG16/17/18 support.
- [Debezium PostgreSQL connector docs](https://debezium.io/documentation/reference/stable/connectors/postgresql.html) — HIGH confidence.
- [Testcontainers Postgres module](https://java.testcontainers.org/modules/databases/postgres/) — HIGH confidence on custom image pattern.
- [Spring Cloud Vault project page](https://spring.io/projects/spring-cloud-vault/) and [reference docs](https://docs.spring.io/spring-cloud-vault/docs/current/reference/html/) — HIGH confidence on Config Data API.
- [Apache AGE JDBC driver source](https://github.com/apache/age/tree/master/drivers/jdbc) — verified that the official pgJDBC works without the AGE JDBC driver for basic Cypher usage.

---

*Stack research for: graph-based protocol-agnostic integration layer (Tessera)*
*Researched: 2026-04-13*
