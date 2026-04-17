{/* Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */}

# Circlead Integration: Entity Mapping Reference

**Requirements:** CIRC-01, CIRC-02, CIRC-03  
**ADR:** ADR-6 (circlead stays standalone, consumes Tessera in parallel)

---

## 1. Overview

circlead is the first real consumer of Tessera. Per ADR-6, circlead retains its own JPA model and continues operating independently. Tessera integration is additive and runs in parallel:

- **Tessera → circlead (read direction):** circlead reads entity data via Tessera's REST projection (`GET /api/v1/{model}/entities/{typeSlug}`) and MCP tools in parallel with its own JPA queries. This requires no changes to circlead's write path.
- **circlead → Tessera (ingest direction):** A Tessera REST connector polls circlead's existing API to ingest Role, Circle, and Activity data as graph nodes. This uses the `GenericRestPollerConnector` with mapping definitions in `fabric-connectors/src/main/resources/connectors/`.

The graph is the truth — REST and MCP projections are generated from the central meta-model. circlead data ingested via the connector becomes part of the graph and is accessible to all Tessera consumers (LLM agents, BI tools, dashboards).

---

## 2. Entity Mapping

### 2.1 Role

**Circlead entity class:** `WorkItem` (subtype discriminator: `type=ROLE`)  
**Tessera target node type slug:** `role`  
**Identity field(s):** `circlead_id`

| Circlead field path | Tessera property | Data type | Required | Notes |
|---------------------|-----------------|-----------|----------|-------|
| `$.id`              | `circlead_id`   | String    | Yes      | WorkItem UUID; used as identity field for delta detection |
| `$.title`           | `title`         | String    | Yes      | Human-readable role name |
| `$.abbreviation`    | `abbreviation`  | String    | No       | Short code, e.g. "PO" |
| `$.purpose`         | `purpose`       | String    | No       | Purpose statement |
| `$.status`          | `status`        | String    | No       | e.g. ACTIVE, INACTIVE |
| `$.type.name`       | `role_type`     | String    | No       | Role sub-type name |

**Mapping resource:** `classpath:connectors/circlead-role-mapping.json`  
**Spring bean:** `circleadRoleMapping` (via `CircleadConnectorConfig`)

**Edge types (requires Schema Registry configuration before activation):**

| Edge type        | From   | To       | Description |
|-----------------|--------|----------|-------------|
| `BELONGS_TO`    | Role   | Circle   | Role is assigned to a Circle |
| `RESPONSIBLE_FOR` | Role | Activity | Role is responsible for an Activity |

These edge types must be registered in the Tessera Schema Registry (via `POST /admin/schema/{model}/edge-types`) before the connector can create edges. The current connector ingests node data only — edge creation requires a separate mapping pass once edge type schemas are registered.

---

### 2.2 Circle

**Circlead entity class:** `WorkItem` (subtype discriminator: `type=CIRCLE`)  
**Tessera target node type slug:** `circle`  
**Identity field(s):** `circlead_id`

| Circlead field path | Tessera property | Data type | Required | Notes |
|---------------------|-----------------|-----------|----------|-------|
| `$.id`              | `circlead_id`   | String    | Yes      | WorkItem UUID; identity field |
| `$.title`           | `title`         | String    | Yes      | Circle name |
| `$.abbreviation`    | `abbreviation`  | String    | No       | Short code |
| `$.purpose`         | `purpose`       | String    | No       | Circle purpose statement |
| `$.status`          | `status`        | String    | No       | e.g. ACTIVE, INACTIVE |

**Mapping resource:** `classpath:connectors/circlead-circle-mapping.json`  
**Spring bean:** `circleadCircleMapping` (via `CircleadConnectorConfig`)

**Edge types (requires Schema Registry configuration before activation):**

| Edge type    | From   | To     | Description |
|-------------|--------|--------|-------------|
| `PARENT_OF` | Circle | Circle | Hierarchical circle nesting |

---

### 2.3 Activity

**Circlead entity class:** `WorkItem` (subtype discriminator: `type=ACTIVITY`)  
**Tessera target node type slug:** `activity`  
**Identity field(s):** `circlead_id`

| Circlead field path | Tessera property | Data type | Required | Notes |
|---------------------|-----------------|-----------|----------|-------|
| `$.id`              | `circlead_id`   | String    | Yes      | WorkItem UUID; identity field |
| `$.title`           | `title`         | String    | Yes      | Activity name |
| `$.abbreviation`    | `abbreviation`  | String    | No       | Short code |
| `$.purpose`         | `purpose`       | String    | No       | Activity purpose statement |
| `$.status`          | `status`        | String    | No       | e.g. ACTIVE, INACTIVE |

**Mapping resource:** `classpath:connectors/circlead-activity-mapping.json`  
**Spring bean:** `circleadActivityMapping` (via `CircleadConnectorConfig`)

---

## 3. Circlead REST API Endpoints

The connector polls these three endpoints on circlead's existing API:

| Entity   | Method | URL pattern |
|----------|--------|-------------|
| Role     | GET    | `/circlead/workitem/list?type=ROLE&details=true` |
| Circle   | GET    | `/circlead/workitem/list?type=CIRCLE&details=true` |
| Activity | GET    | `/circlead/workitem/list?type=ACTIVITY&details=true` |

**Response format:** `RestEnvelope` with a `content` array:

```json
{
  "content": [
    {
      "id": "role-001",
      "title": "Product Owner",
      "abbreviation": "PO",
      "purpose": "Define the product vision",
      "status": "ACTIVE",
      "type": { "name": "role" }
    }
  ],
  "status": 200
}
```

The `GenericRestPollerConnector` extracts rows using the JSONPath root path `$.content[*]` and maps each row to a `CandidateMutation` via the field mappings defined in the JSON classpath resources.

---

## 4. Connector Configuration

### 4.1 application.yml

```yaml
tessera:
  connectors:
    circlead:
      base-url: "https://circlead.example.com"   # circlead API base URL
```

### 4.2 Connector registration (POST /admin/connectors)

Register one connector per entity type. Example for Role:

```json
{
  "type": "rest-poll",
  "authType": "BEARER",
  "credentialsRef": "secret/tessera/connectors/circlead/bearer_token",
  "pollIntervalSeconds": 300,
  "mappingDef": {
    "sourceEntityType": "role",
    "targetNodeTypeSlug": "role",
    "rootPath": "$.content[*]",
    "sourceUrl": "https://circlead.example.com/circlead/workitem/list?type=ROLE&details=true",
    "identityFields": ["circlead_id"],
    "fields": [
      {"target": "circlead_id",  "sourcePath": "$.id",          "required": true},
      {"target": "title",        "sourcePath": "$.title",        "required": true},
      {"target": "abbreviation", "sourcePath": "$.abbreviation", "required": false},
      {"target": "purpose",      "sourcePath": "$.purpose",      "required": false},
      {"target": "status",       "sourcePath": "$.status",       "required": false},
      {"target": "role_type",    "sourcePath": "$.type.name",    "required": false}
    ]
  }
}
```

Repeat with `type=CIRCLE` and `type=ACTIVITY` for the other two entity types.

### 4.3 Polling interval

Default: **300 seconds (5 minutes)**. circlead data changes at human pace; 5-minute polling is sufficient for MVP. Adjust `pollIntervalSeconds` per connector registration if tighter latency is required.

### 4.4 Authentication

circlead's API accepts Bearer token authentication. The token must be stored in Vault at the path specified in `credentialsRef` (e.g. `secret/tessera/connectors/circlead/bearer_token`). Tessera's `ConnectorRunner` retrieves the token from Vault at poll time and passes it via `ConnectorState.customState["bearer_token"]` — the token is never stored in Postgres or in the mapping definition JSON.

---

## 5. Graceful Degradation (CIRC-03)

Graceful degradation applies on the **circlead side** — when circlead's own code calls Tessera's REST or MCP projections, it must tolerate Tessera unavailability and fall back to its local JPA data.

### 5.1 Recommended pattern: Spring Retry

Add `spring-retry` and `spring-aspects` to circlead's `pom.xml`, then annotate Tessera REST calls with `@Retryable`:

```java
// In circlead's TesseraClient or service layer
@Service
public class TesseraRoleClient {

    private final RestTemplate restTemplate;

    @Retryable(
        retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)  // 1s, 2s exponential
    )
    public List<RoleDto> fetchRolesFromTessera(String modelId) {
        return restTemplate.exchange(
            "https://tessera.example.com/api/v1/{model}/entities/role",
            HttpMethod.GET,
            buildRequest(),
            new ParameterizedTypeReference<List<RoleDto>>() {}
        ).getBody();
    }

    @Recover
    public List<RoleDto> fetchRolesFallback(Exception ex, String modelId) {
        // Fall back to circlead's own JPA repository
        log.warn("Tessera unavailable ({}), falling back to local JPA data", ex.getMessage());
        return roleRepository.findAll().stream()
            .map(RoleDto::fromWorkItem)
            .toList();
    }
}
```

Enable Spring Retry in circlead's configuration:

```java
@Configuration
@EnableRetry
public class TesseraIntegrationConfig { ... }
```

### 5.2 Behavior

| Tessera state | Attempt 1 | Attempt 2 | Attempt 3 | Result |
|---------------|-----------|-----------|-----------|--------|
| Available     | Success   | —         | —         | Tessera data served |
| Transient error | Failure | Success   | —         | Tessera data served (after retry) |
| Unavailable   | Failure   | Failure   | Failure   | `@Recover` fires → local JPA fallback |

The fallback is transparent to circlead's callers. Tessera data is preferred when available; local JPA data is served when retries are exhausted. No data is lost — circlead's JPA model remains the authoritative source for circlead-owned entities.

### 5.3 Note on direction

This circuit breaker applies to **circlead → Tessera** calls (circlead reading from Tessera's projections). It is **circlead-side code**, not Tessera-side. Tessera's own connector polling (Tessera → circlead API) is protected separately by the existing `WriteRateCircuitBreaker` and `ShedLock`-based scheduler deduplication.

---

## 6. Round-Trip Verification

After the connector has run at least one successful poll cycle, verify the mapping end-to-end:

### 6.1 REST projection check

```bash
# 1. Trigger a manual poll (or wait for the scheduled interval)
# 2. Query via the REST projection
curl -H "Authorization: Bearer <token>" \
  "https://tessera.example.com/api/v1/<model_id>/entities/role" | jq '.[] | {circlead_id, title, status}'
```

Expected: each object has `circlead_id` matching the circlead WorkItem UUID, `title` and `status` matching the source data.

### 6.2 MCP tool verification

Using an MCP client connected to Tessera's MCP projection:

```
Tool: list_entity_types
Arguments: { "model_id": "<model_id>" }
Expected: "role", "circle", "activity" appear in the response

Tool: get_entity
Arguments: { "model_id": "<model_id>", "type_slug": "role", "node_id": "<uuid>" }
Expected: properties include circlead_id, title, abbreviation, purpose, status
```

### 6.3 Field-level assertions

| Check | Expected |
|-------|----------|
| `circlead_id` equals circlead WorkItem UUID | Yes |
| `title` matches circlead `title` field | Yes |
| `_source_hash` present (dedup field) | Yes (added by connector) |
| No cross-tenant data leakage | Verified by `model_id` filter on all queries |
