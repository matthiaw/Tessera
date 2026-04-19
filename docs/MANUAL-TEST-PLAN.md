# Manueller Testplan: Tessera v1.0 MVP

**Stand:** 2026-04-18
**Zweck:** Hands-on Verständnis aller Tessera-Features durch manuelle Tests

---

## Inhaltsverzeichnis

1. [Was kann Tessera?](#1-was-kann-tessera)
2. [Voraussetzungen & Start](#2-voraussetzungen--start)
3. [Test 1: Health Check & OpenAPI](#test-1-health-check--openapi)
4. [Test 2: Bootstrap-Token holen](#test-2-bootstrap-token-holen)
5. [Test 3: Schema anlegen (Node Types)](#test-3-schema-anlegen-node-types)
6. [Test 4: Entities über REST anlegen & abfragen](#test-4-entities-über-rest-anlegen--abfragen)
7. [Test 5: Entity aktualisieren & löschen](#test-5-entity-aktualisieren--löschen)
8. [Test 6: Cursor-Pagination testen](#test-6-cursor-pagination-testen)
9. [Test 7: Tenant-Isolation prüfen](#test-7-tenant-isolation-prüfen)
10. [Test 8: MCP-Tools für LLM-Agents](#test-8-mcp-tools-für-llm-agents)
11. [Test 9: Event Log & Temporale Abfragen](#test-9-event-log--temporale-abfragen)
12. [Test 10: Connector anlegen & verwalten](#test-10-connector-anlegen--verwalten)
13. [Test 11: Rule Engine & Reconciliation](#test-11-rule-engine--reconciliation)
14. [Test 12: SQL-View-Projektion](#test-12-sql-view-projektion)
15. [Test 13: Audit-Integrität (Hash-Chain)](#test-13-audit-integrität-hash-chain)
16. [Test 14: Event-Snapshots & Retention](#test-14-event-snapshots--retention)
17. [Test 15: Extraction Review Queue](#test-15-extraction-review-queue)
18. [Test 16: Kafka-Projektion (optional)](#test-16-kafka-projektion-optional)

---

## 1. Was kann Tessera?

Tessera ist ein graphbasierter Integrations-Layer. Der Graph ist die Wahrheit — REST, MCP, SQL Views und Kafka Topics sind alles **Projektionen** derselben Daten.

### Features im v1.0 MVP

| Feature | Beschreibung |
|---------|-------------|
| **Knowledge Graph** | PostgreSQL + Apache AGE — Nodes, Edges, Properties, Cypher-Abfragen |
| **Schema Registry** | Node Types, Properties, Edge Types definieren; SHACL-Validierung |
| **Event Log** | Jede Mutation wird als Event gespeichert (Wer, Wann, Was, Woher) |
| **REST-Projektion** | Dynamisch generierte REST-Endpoints pro Entity-Typ |
| **MCP-Projektion** | 7 MCP-Tools für LLM-Agents (list, describe, query, traverse, ...) |
| **SQL-View-Projektion** | Auto-generierte SQL-Views für BI-Tools (Metabase, PowerBI, ...) |
| **Kafka-Projektion** | Debezium CDC → Kafka Topics pro Entity-Typ (optional) |
| **Connector Framework** | REST-Poller, Markdown-Ordner, Mapping-Definitionen, Delta-Detection |
| **Unstructured Extraction** | LLM-basierte Entity-Extraktion aus Freitext (Claude) |
| **Rule Engine** | Chain-of-Responsibility: Reconciliation, SHACL-Validierung, Echo-Loop-Schutz |
| **Source Authority Matrix** | Pro Property festlegen, welches Quellsystem die Wahrheit hat |
| **Multi-Tenant-Isolation** | Jeder Node, Edge, Event ist per `model_id` isoliert |
| **JWT-Authentifizierung** | HS256-signierte Tokens mit Tenant- und Rollen-Claims |
| **Hash-Chained Audit** | Manipulationssichere Event-Kette (für GxP/SOX/BSI C5) |
| **Temporale Abfragen** | "Wie sah Entity X am 1. März aus?" — per Event-Replay |
| **Circlead-Integration** | Erste echte Consumer-Anbindung (Role, Circle, Activity) |

---

## 2. Voraussetzungen & Start

### Benötigt

- **Docker** (Docker Compose v2)
- **Java 21** (OpenJDK/Corretto)
- **Maven 3.9+**
- **curl** und **jq** (für Tests)
- Optional: **psql** (PostgreSQL-Client für SQL-View-Tests)

### Schritt 1: Docker-Services starten

```bash
cd ~/Programmming/GitHub/Tessera

# PostgreSQL + AGE + Ollama starten
docker compose up -d postgres-age ollama ollama-init
```

Warten bis die Services healthy sind:

```bash
docker compose ps
# postgres-age sollte "healthy" zeigen
```

### Schritt 2: Tessera bauen

```bash
mvn clean install -DskipTests -Dspotless.check.skip=true
```

> **Hinweis:** `-Dspotless.check.skip=true` ist nötig, weil Spotless 2.44.1 auf JDK 23 inkompatibel ist.

### Schritt 3: Umgebungsvariablen setzen

```bash
# JWT-Signaturschlüssel (min. 32 Bytes, Base64-kodiert)
export TESSERA_JWT_SIGNING_KEY=$(openssl rand -base64 32)

# Bootstrap-Token für den ersten Admin-Zugang
export TESSERA_BOOTSTRAP_TOKEN="mein-geheimer-bootstrap-token"
```

### Schritt 4: Tessera starten

```bash
mvn spring-boot:run -pl fabric-app
```

Die Anwendung läuft auf `http://localhost:8080`.

### Schritt 5: Tenant-UUID festlegen

Für alle Tests verwenden wir eine feste Tenant-UUID:

```bash
export MODEL_ID="550e8400-e29b-41d4-a716-446655440000"
```

---

## Test 1: Health Check & OpenAPI

**Ziel:** Prüfen ob Tessera läuft und die API-Dokumentation erreichbar ist.

### 1.1 Health-Endpoint

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "status": "UP"
}
```

> **Was passiert:** Spring Boot Actuator prüft Datenbank-Verbindung, AGE-Extension und Flyway-Migrationen.

### 1.2 OpenAPI / Swagger UI

Öffne im Browser:

```
http://localhost:8080/swagger-ui.html
```

Oder die rohe API-Spec:

```bash
curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'
```

**Erwartetes Ergebnis:** Liste aller REST-Endpoints.

> **Was passiert:** SpringDoc generiert die OpenAPI-Spec dynamisch aus den registrierten Controllern. Neue Entity-Typen erscheinen hier automatisch.

---

## Test 2: Bootstrap-Token holen

**Ziel:** Einen JWT-Token ausstellen, mit dem alle Admin-Operationen möglich sind.

```bash
curl -s -X POST http://localhost:8080/admin/tokens/issue \
  -H "X-Tessera-Bootstrap: ${TESSERA_BOOTSTRAP_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"tenant\": \"${MODEL_ID}\",
    \"roles\": [\"ADMIN\", \"AGENT\"]
  }" | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_at": "2026-04-18T..."
}
```

**Token speichern:**

```bash
export TOKEN=$(curl -s -X POST http://localhost:8080/admin/tokens/issue \
  -H "X-Tessera-Bootstrap: ${TESSERA_BOOTSTRAP_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"tenant\": \"${MODEL_ID}\", \"roles\": [\"ADMIN\", \"AGENT\"]}" \
  | jq -r '.token')

echo "Token: ${TOKEN:0:20}..."
```

> **Was passiert:** Der Bootstrap-Endpoint ist öffentlich, aber nur mit dem richtigen `X-Tessera-Bootstrap`-Header aufrufbar. Er erzeugt einen HS256-signierten JWT mit den Claims `tenant`, `roles`, `exp`, `iat`, `iss`, `sub`. Gültigkeitsdauer: 15 Minuten (konfigurierbar via `tessera.auth.token-ttl-minutes`).

---

## Test 3: Schema anlegen (Node Types)

**Ziel:** Entity-Typen im Schema Registry definieren. Ohne Schema kann kein Entity angelegt werden.

> **Hinweis:** Die Schema-Registry hat im MVP keinen eigenen REST-Endpoint. Typen werden über direkte Datenbank-Inserts oder programmatisch angelegt. Für manuelle Tests nutzen wir `psql`.

### 3.1 Node Type "Person" anlegen

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera <<'SQL'
INSERT INTO schema_node_types (id, model_id, name, slug, label, description, builtin, rest_read_enabled, rest_write_enabled)
VALUES (
  gen_random_uuid(),
  '550e8400-e29b-41d4-a716-446655440000'::uuid,
  'Person',
  'person',
  'Person',
  'Eine natürliche Person',
  false,
  true,
  true
);
SQL
```

### 3.2 Properties definieren

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera <<'SQL'
INSERT INTO schema_properties (id, model_id, type_slug, name, slug, data_type, required)
VALUES
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'person', 'Name', 'name', 'STRING', true),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'person', 'Email', 'email', 'STRING', false),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'person', 'Alter', 'age', 'INTEGER', false);
SQL
```

### 3.3 Node Type "Organization" anlegen

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera <<'SQL'
INSERT INTO schema_node_types (id, model_id, name, slug, label, description, builtin, rest_read_enabled, rest_write_enabled)
VALUES (
  gen_random_uuid(),
  '550e8400-e29b-41d4-a716-446655440000'::uuid,
  'Organization',
  'organization',
  'Organization',
  'Eine Organisation oder Firma',
  false,
  true,
  true
);

INSERT INTO schema_properties (id, model_id, type_slug, name, slug, data_type, required)
VALUES
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'organization', 'Name', 'name', 'STRING', true),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'organization', 'Website', 'website', 'STRING', false);
SQL
```

### 3.4 Edge Type definieren

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera <<'SQL'
INSERT INTO schema_edge_types (id, model_id, name, slug, edge_label, inverse_name, source_type_slug, target_type_slug, cardinality)
VALUES (
  gen_random_uuid(),
  '550e8400-e29b-41d4-a716-446655440000'::uuid,
  'Works At',
  'works_at',
  'WORKS_AT',
  'employs',
  'person',
  'organization',
  'MANY_TO_ONE'
);
SQL
```

### 3.5 Schema prüfen

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera -c \
  "SELECT slug, name, rest_read_enabled FROM schema_node_types WHERE model_id = '550e8400-e29b-41d4-a716-446655440000'::uuid;"
```

**Erwartetes Ergebnis:**
```
    slug     |     name     | rest_read_enabled
-------------+--------------+-------------------
 person      | Person       | t
 organization| Organization | t
```

> **Was passiert:** Die Schema Registry speichert Metadaten über Entity-Typen, ihre Properties und Beziehungen. Diese Metadaten steuern: welche REST-Endpoints generiert werden, welche SHACL-Shapes für Validierung gebaut werden, welche MCP-Tools verfügbar sind, und welche SQL-Views erzeugt werden.

---

## Test 4: Entities über REST anlegen & abfragen

**Ziel:** Über die dynamisch generierten REST-Endpoints Entities erstellen und lesen.

### 4.1 Person anlegen

```bash
curl -s -X POST "http://localhost:8080/api/v1/${MODEL_ID}/entities/person" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice Schmidt",
    "email": "alice@example.com",
    "age": 32
  }' | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "uuid": "...",
  "seq": 1
}
```

**UUID speichern:**

```bash
export ALICE_UUID=$(curl -s -X POST "http://localhost:8080/api/v1/${MODEL_ID}/entities/person" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name": "Bob Müller", "email": "bob@example.com", "age": 45}' \
  | jq -r '.uuid')

echo "Bob UUID: ${ALICE_UUID}"
```

### 4.2 Organization anlegen

```bash
curl -s -X POST "http://localhost:8080/api/v1/${MODEL_ID}/entities/organization" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Tessera GmbH",
    "website": "https://tessera.dev"
  }' | jq .
```

### 4.3 Alle Personen abrufen

```bash
curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "items": [
    {
      "uuid": "...",
      "type": "person",
      "seq": 1,
      "name": "Alice Schmidt",
      "email": "alice@example.com",
      "age": 32,
      "created_at": "...",
      "updated_at": "..."
    },
    ...
  ],
  "next_cursor": null
}
```

### 4.4 Einzelne Person per UUID

```bash
curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person/${ALICE_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

> **Was passiert:** Der `GenericEntityController` nimmt den `{typeSlug}` aus der URL, prüft ob dieser Typ im Schema existiert und `rest_read_enabled` ist, validiert den JWT-Tenant-Claim gegen `{model}`, und führt die Operation als Graph-Mutation durch `GraphService.apply()` aus. Jede Mutation erzeugt einen Event-Log-Eintrag.

---

## Test 5: Entity aktualisieren & löschen

### 5.1 Person aktualisieren

```bash
curl -s -X PUT "http://localhost:8080/api/v1/${MODEL_ID}/entities/person/${ALICE_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "bob.mueller@neuemail.de"
  }' | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "uuid": "...",
  "seq": 3
}
```

> Die `seq`-Nummer steigt — jede Mutation bekommt eine neue Sequenznummer.

### 5.2 Person löschen (Soft-Delete / Tombstone)

```bash
curl -s -X DELETE "http://localhost:8080/api/v1/${MODEL_ID}/entities/person/${ALICE_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "uuid": "...",
  "tombstoned": true
}
```

> **Was passiert:** Tessera macht kein hartes DELETE. Der Node bekommt einen `_deleted_at`-Timestamp. Er taucht nicht mehr in normalen Queries auf, aber der Event-Log behält die komplette Historie.

### 5.3 Prüfen: Gelöschte Person nicht mehr abrufbar

```bash
curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person/${ALICE_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:** 404 Not Found

---

## Test 6: Cursor-Pagination testen

**Ziel:** Bei vielen Entities greift die Cursor-basierte Pagination.

### 6.1 Mehrere Personen anlegen

```bash
for i in $(seq 1 5); do
  curl -s -X POST "http://localhost:8080/api/v1/${MODEL_ID}/entities/person" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"name\": \"Person ${i}\", \"email\": \"person${i}@test.de\"}" > /dev/null
done
echo "5 Personen angelegt"
```

### 6.2 Erste Seite abrufen (Limit 2)

```bash
curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person?limit=2" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:** 2 Items + ein `next_cursor`-Wert.

### 6.3 Nächste Seite mit Cursor

```bash
CURSOR=$(curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person?limit=2" \
  -H "Authorization: Bearer ${TOKEN}" | jq -r '.next_cursor')

curl -s "http://localhost:8080/api/v1/${MODEL_ID}/entities/person?limit=2&cursor=${CURSOR}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

> **Was passiert:** Tessera nutzt Seek-Method-Pagination (nicht OFFSET). Der Cursor kodiert die letzte `_seq`-Nummer. Das ist schneller und stabiler als OFFSET bei großen Datenmengen.

---

## Test 7: Tenant-Isolation prüfen

**Ziel:** Beweisen, dass ein Token für Tenant A keine Daten von Tenant B sehen kann.

### 7.1 Token für anderen Tenant holen

```bash
export OTHER_MODEL="99999999-9999-9999-9999-999999999999"

export OTHER_TOKEN=$(curl -s -X POST http://localhost:8080/admin/tokens/issue \
  -H "X-Tessera-Bootstrap: ${TESSERA_BOOTSTRAP_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"tenant\": \"${OTHER_MODEL}\", \"roles\": [\"ADMIN\"]}" \
  | jq -r '.token')
```

### 7.2 Versuch: Daten des ersten Tenants lesen

```bash
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/api/v1/${MODEL_ID}/entities/person" \
  -H "Authorization: Bearer ${OTHER_TOKEN}"
```

**Erwartetes Ergebnis:** `404` (nicht 403 — um keine Existenz zu verraten)

### 7.3 Versuch: Mit erstem Token anderen Tenant ansprechen

```bash
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/api/v1/${OTHER_MODEL}/entities/person" \
  -H "Authorization: Bearer ${TOKEN}"
```

**Erwartetes Ergebnis:** `404`

> **Was passiert:** Der JWT enthält einen `tenant`-Claim. Wenn der Pfad-Parameter `{model}` nicht zum Token-Tenant passt, gibt Tessera 404 zurück (niemals 403, um Cross-Tenant-Probing zu verhindern). Alle Datenbank-Queries filtern immer auf `model_id`.

---

## Test 8: MCP-Tools für LLM-Agents

**Ziel:** Die MCP-Projektion testen — die Tools, mit denen LLM-Agents den Graphen abfragen.

> **Hinweis:** Die MCP-Projektion nutzt Server-Sent Events (SSE). Für manuelle Tests nutzen wir den SSE-Endpoint direkt. In der Praxis verbinden sich Agents über das MCP-Protokoll.

### 8.1 Entity-Typen auflisten

```bash
curl -s -X POST "http://localhost:8080/mcp/message" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "list_entity_types",
    "arguments": {}
  }' | jq .
```

**Erwartetes Ergebnis:** Liste aller definierten Typen (person, organization).

### 8.2 Typ beschreiben

```bash
curl -s -X POST "http://localhost:8080/mcp/message" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "describe_type",
    "arguments": {"slug": "person"}
  }' | jq .
```

**Erwartetes Ergebnis:** Typ-Definition mit Properties (name, email, age) und Edge-Types.

### 8.3 Entities abfragen

```bash
curl -s -X POST "http://localhost:8080/mcp/message" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "query_entities",
    "arguments": {"type": "person", "limit": 10}
  }' | jq .
```

### 8.4 Cypher-Traversal

```bash
curl -s -X POST "http://localhost:8080/mcp/message" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "traverse",
    "arguments": {"query": "MATCH (p:Person) RETURN p.name, p.email LIMIT 5"}
  }' | jq .
```

### 8.5 MCP-Audit-Log prüfen

```bash
curl -s "http://localhost:8080/admin/mcp/audit?model_id=${MODEL_ID}&limit=10" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:** Einträge für jeden MCP-Tool-Aufruf mit `tool_name`, `agent_id`, `duration_ms`, `outcome`.

> **Was passiert:** Jeder MCP-Tool-Aufruf wird im `mcp_audit_log` protokolliert. Agents sind standardmäßig read-only — Schreibversuche ohne explizite Write-Quota werden abgelehnt. Cypher-Abfragen werden gegen Mutation-Keywords (CREATE, DELETE, MERGE, SET) geprüft.

---

## Test 9: Event Log & Temporale Abfragen

**Ziel:** Die Event-basierte Historienverwaltung testen.

### 9.1 Event Log direkt abfragen (SQL)

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera -c \
  "SELECT id, node_id, operation, source, seq, created_at
   FROM graph_events
   WHERE model_id = '550e8400-e29b-41d4-a716-446655440000'::uuid
   ORDER BY seq DESC
   LIMIT 10;"
```

**Erwartetes Ergebnis:** Alle bisherigen CREATE/UPDATE/DELETE-Events mit Sequenznummern.

### 9.2 Temporale Abfrage: "Zustand zu einem Zeitpunkt"

```bash
# Erst eine Person anlegen und den Zeitpunkt merken
BEFORE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# ... Person aktualisieren (Test 5.1) ...

# Dann den Zustand VOR dem Update abfragen:
curl -s -X POST "http://localhost:8080/mcp/message" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"tool\": \"get_state_at\",
    \"arguments\": {
      \"entity_id\": \"${ALICE_UUID}\",
      \"timestamp\": \"${BEFORE}\"
    }
  }" | jq .
```

**Erwartetes Ergebnis:** Der Zustand der Person **vor** dem Update — z.B. die alte E-Mail-Adresse.

> **Was passiert:** Tessera speichert nicht nur den aktuellen Zustand, sondern jede Mutation als Event. `get_state_at` spielt die Events bis zum angegebenen Zeitpunkt ab und rekonstruiert den damaligen Zustand. Das ermöglicht Audit-Fragen wie "Was wussten wir am 1. März über diese Person?"

---

## Test 10: Connector anlegen & verwalten

**Ziel:** Einen REST-Poller-Connector konfigurieren und seinen Status prüfen.

### 10.1 Connector anlegen

```bash
curl -s -X POST "http://localhost:8080/admin/connectors" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "rest",
    "authType": "NONE",
    "credentialsRef": "vault:secret/tessera/test/api-token",
    "pollIntervalSeconds": 300,
    "mappingDef": {
      "sourceType": "REST",
      "baseUrl": "https://jsonplaceholder.typicode.com",
      "pollPath": "/users",
      "extractorFields": [
        {"sourceField": "id", "targetProperty": "source_id", "required": true},
        {"sourceField": "name", "targetProperty": "name"},
        {"sourceField": "email", "targetProperty": "email"}
      ],
      "typeSlugMapping": {"person": "users"},
      "reconciliationPolicy": "LAST_WRITE_WINS",
      "deltaDetection": {"strategy": "NONE"}
    }
  }' | jq .
```

### 10.2 Connector-Liste

```bash
curl -s "http://localhost:8080/admin/connectors" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 10.3 Connector-Status prüfen

```bash
CONNECTOR_ID=$(curl -s "http://localhost:8080/admin/connectors" \
  -H "Authorization: Bearer ${TOKEN}" | jq -r '.[0].id')

curl -s "http://localhost:8080/admin/connectors/${CONNECTOR_ID}/status" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "connector_id": "...",
  "last_outcome": "NEVER_POLLED",
  "events_processed": 0,
  "dlq_count": 0,
  "next_poll_at": "..."
}
```

### 10.4 Dead Letter Queue (DLQ) prüfen

```bash
curl -s "http://localhost:8080/admin/connectors/${CONNECTOR_ID}/dlq" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

> **Was passiert:** Connectors sind die "Südseite" von Tessera — sie holen Daten aus externen Systemen. Jeder Connector hat eine `MappingDefinition`, die beschreibt: woher die Daten kommen, wie Source-Felder auf Graph-Properties gemappt werden, welche Reconciliation-Policy gilt, und wie Delta-Detection funktioniert. Fehlgeschlagene Imports landen in der DLQ.

---

## Test 11: Rule Engine & Reconciliation

**Ziel:** Zeigen, wie die Source Authority Matrix und die Conflict Resolution arbeiten.

### 11.1 Authority Matrix einrichten

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera <<'SQL'
INSERT INTO source_authority (id, model_id, property_slug, authoritative_source, created_at)
VALUES
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'name', 'hr-system', now()),
  (gen_random_uuid(), '550e8400-e29b-41d4-a716-446655440000'::uuid, 'email', 'crm', now());
SQL
```

> **Was passiert:** Jetzt ist `hr-system` die autoritative Quelle für das Property `name` und `crm` für `email`. Wenn ein anderer Connector versucht, diese Properties zu überschreiben, wird die Mutation abgelehnt.

### 11.2 Reconciliation-Konflikte prüfen

```bash
docker exec -i tessera-postgres-age psql -U tessera -d tessera -c \
  "SELECT id, node_id, candidate_source, conflict_reason, created_at
   FROM reconciliation_conflicts
   WHERE model_id = '550e8400-e29b-41d4-a716-446655440000'::uuid
   ORDER BY created_at DESC
   LIMIT 10;"
```

### 11.3 Circuit Breaker zurücksetzen

```bash
curl -s -X POST "http://localhost:8080/admin/connectors/${CONNECTOR_ID}/reset" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

> **Was passiert:** Wenn ein Connector in einer Schleife zu viele Mutations erzeugt (Write Amplification), greift der Circuit Breaker und stoppt ihn. Admin kann ihn über diesen Endpoint manuell zurücksetzen.

---

## Test 12: SQL-View-Projektion

**Ziel:** Prüfen ob SQL-Views für BI-Tools generiert werden.

### 12.1 Generierte Views auflisten

```bash
curl -s "http://localhost:8080/admin/sql/views?model_id=${MODEL_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 12.2 View direkt per SQL abfragen

```bash
# View-Name aus der API-Antwort nehmen, z.B.:
docker exec -i tessera-postgres-age psql -U tessera -d tessera -c \
  "SELECT * FROM v_550e8400e29b41d4a716446655440000_person LIMIT 5;"
```

**Erwartetes Ergebnis:** Relationale Tabelle mit Spalten `uuid`, `name`, `email`, `age`, `created_at`, `updated_at`.

> **Was passiert:** Tessera erzeugt für jeden Entity-Typ pro Tenant eine SQL-View, die direkt auf die AGE-Graph-Daten zugreift. BI-Tools (Metabase, PowerBI, Looker) können sich per JDBC/ODBC verbinden und diese Views wie normale Tabellen abfragen — ohne Graph-Kenntnisse.

---

## Test 13: Audit-Integrität (Hash-Chain)

**Ziel:** Prüfen ob die Event-Log-Hashkette intakt ist.

```bash
curl -s -X POST "http://localhost:8080/admin/audit/verify?model_id=${MODEL_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis (intakt):**
```json
{
  "valid": true,
  "events_checked": 15
}
```

**Bei Manipulation:**
```json
{
  "valid": false,
  "events_checked": 8,
  "broken_at_seq": 9,
  "expected_hash": "abc...",
  "actual_hash": "xyz..."
}
```

> **Was passiert:** Jeder Event-Log-Eintrag enthält den Hash des vorherigen Eintrags plus seinen eigenen Payload. Das erzeugt eine Kette, bei der jede Manipulation auffällt. Gedacht für Compliance (GxP, SOX, BSI C5).

---

## Test 14: Event-Snapshots & Retention

**Ziel:** Event-Log kompaktieren und Retention konfigurieren.

### 14.1 Retention abfragen

```bash
curl -s "http://localhost:8080/admin/events/retention?model_id=${MODEL_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 14.2 Retention setzen (z.B. 90 Tage)

```bash
curl -s -X PUT "http://localhost:8080/admin/events/retention?model_id=${MODEL_ID}&retention_days=90" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 14.3 Snapshot erstellen

```bash
curl -s -X POST "http://localhost:8080/admin/events/snapshot?model_id=${MODEL_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

**Erwartetes Ergebnis:**
```json
{
  "model_id": "...",
  "snapshot_boundary": "seq_15",
  "events_written": 15,
  "events_deleted": 12
}
```

> **Was passiert:** Snapshots kompaktieren den Event-Log. Alte Events werden archiviert und gelöscht — temporale Abfragen funktionieren weiterhin oberhalb der Snapshot-Grenze.

---

## Test 15: Extraction Review Queue

**Ziel:** Die Warteschlange für LLM-extrahierte Entities prüfen.

### 15.1 Review Queue abrufen

```bash
curl -s "http://localhost:8080/admin/extraction/review?limit=10" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 15.2 Kandidat akzeptieren

```bash
# REVIEW_ID aus der Queue-Antwort:
curl -s -X POST "http://localhost:8080/admin/extraction/review/${REVIEW_ID}/accept" \
  -H "Authorization: Bearer ${TOKEN}" | jq .
```

### 15.3 Kandidat ablehnen

```bash
curl -s -X POST "http://localhost:8080/admin/extraction/review/${REVIEW_ID}/reject" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Duplikat von existierendem Node"}' | jq .
```

> **Was passiert:** Wenn ein Unstructured-Connector (z.B. Markdown-Ordner) Text per LLM analysiert, landen die extrahierten Entities erst in einer Review Queue. Ein Admin muss sie explizit akzeptieren, bevor sie in den Graphen übernommen werden. Das ist der "Human-in-the-Loop" für KI-Extraktion.

---

## Test 16: Kafka-Projektion (optional)

**Ziel:** Events über Debezium CDC an Kafka weiterleiten.

### 16.1 Kafka-Stack starten

```bash
docker compose --profile kafka up -d
```

### 16.2 Kafka aktivieren

In `application.yml` oder per Environment-Variable:

```bash
export TESSERA_KAFKA_ENABLED=true
# Tessera neustarten
```

### 16.3 Kafka-Topics prüfen

```bash
docker exec tessera-kafka kafka-topics.sh --list --bootstrap-server localhost:9092
```

**Erwartetes Ergebnis:** Topics wie `tessera.person`, `tessera.organization`.

### 16.4 Events konsumieren

```bash
docker exec tessera-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic tessera.person \
  --from-beginning \
  --max-messages 5
```

> **Was passiert:** Debezium liest den `graph_outbox` per Logical Replication (CDC) und routet Events über den Outbox Event Router in Kafka-Topics — ein Topic pro Entity-Typ. Downstream-Consumer (Webhooks, Streaming-Analytics, andere Dienste) können sich darauf subscriben.

---

## Zusammenfassung: Testabdeckung

| # | Test | Feature | Ergebnis |
|---|------|---------|----------|
| 1 | Health & OpenAPI | Infrastruktur | Tessera läuft, API-Docs sichtbar |
| 2 | Bootstrap Token | Authentifizierung | JWT mit Tenant + Rollen |
| 3 | Schema Registry | Meta-Modell | Types, Properties, Edges definiert |
| 4 | Entity CRUD | REST-Projektion | Anlegen, Lesen, Cursor-Pagination |
| 5 | Update & Delete | REST-Projektion | Aktualisieren, Soft-Delete |
| 6 | Pagination | REST-Projektion | Cursor-basierte Seitennavigation |
| 7 | Tenant-Isolation | Security | Kein Cross-Tenant-Zugriff möglich |
| 8 | MCP-Tools | MCP-Projektion | 7 Tools für LLM-Agents |
| 9 | Event Log | Temporalität | Event-Historie, Zeitreisen |
| 10 | Connector Admin | Connector Framework | CRUD, Status, DLQ |
| 11 | Rule Engine | Reconciliation | Authority Matrix, Conflicts |
| 12 | SQL Views | SQL-Projektion | BI-fähige relationale Views |
| 13 | Audit Hash-Chain | Compliance | Manipulationserkennung |
| 14 | Snapshots | Operations | Event-Log-Kompaktierung |
| 15 | Extraction Review | KI-Extraktion | Human-in-the-Loop Queue |
| 16 | Kafka | Kafka-Projektion | CDC → Topics → Consumer |

---

## Troubleshooting

| Problem | Lösung |
|---------|--------|
| `LOAD 'age'` schlägt fehl | Docker-Image neu bauen: `docker compose build postgres-age` |
| 401 Unauthorized | Token abgelaufen (15 Min. TTL) — neuen Token holen (Test 2) |
| 404 auf Entity-Endpoint | Schema-Typ prüfen: existiert er? Ist `rest_read_enabled = true`? |
| Spotless-Fehler beim Build | `-Dspotless.check.skip=true` verwenden (JDK 23 inkompatibel) |
| Ollama-Verbindungsfehler | `docker compose ps` — ollama muss "healthy" sein |
| Flyway-Migration fehlgeschlagen | Datenbank löschen und neu starten: `docker compose down -v && docker compose up -d` |

---

*Erstellt: 2026-04-18 | Tessera v1.0 MVP*
