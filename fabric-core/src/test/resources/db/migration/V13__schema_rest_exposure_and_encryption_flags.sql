-- Phase 2 / Wave 1 / 02-W1-01: Schema Registry exposure + encryption flags
-- (CONTEXT Decisions 2, 5).
--
-- Decision 5: REST exposure is deny-all by default. Adding two booleans on
-- schema_node_types (both FALSE initially); flipping a flag is the admin
-- action Wave 2 wires through `/admin/schema/*/expose`. Wave 1 only adds the
-- columns + threads them into NodeTypeDescriptor so the Schema Registry
-- becomes the single source of truth for "can this type be exposed over REST".
--
-- Decision 2: Field-level encryption is feature-flagged OFF for Phase 2.
-- Adding `property_encrypted` + `property_encrypted_alg` on schema_properties
-- so a later phase can flip the feature flag without a schema migration.
-- SEC-06 startup guard (wave W1 task 2) refuses to boot if any row has
-- property_encrypted=TRUE while tessera.security.field-encryption.enabled is
-- false — fail-closed per Decision 2.

ALTER TABLE schema_node_types
    ADD COLUMN rest_read_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rest_write_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE schema_properties
    ADD COLUMN property_encrypted     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN property_encrypted_alg TEXT NULL;
