-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 2.5 / Plan 01: Widen connectors auth_type to accept NONE for folder connectors (T-02.5-03).
-- Folder connectors have no credentials to store.
ALTER TABLE connectors DROP CONSTRAINT connectors_auth_type_check;
ALTER TABLE connectors ADD CONSTRAINT connectors_auth_type_check CHECK (auth_type IN ('BEARER', 'NONE'));
ALTER TABLE connectors ALTER COLUMN credentials_ref DROP NOT NULL;
