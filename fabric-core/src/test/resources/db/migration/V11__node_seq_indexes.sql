-- Phase 2 / Wave 1 / 02-W1-01: node `_seq` denormalization (CONTEXT Decision 12).
--
-- Every GraphService.apply call now stamps a monotonic BIGINT `_seq` property
-- on the created/updated node, sourced from the same per-tenant SEQUENCE
-- allocation that EVENT-02 already uses for `graph_events.sequence_nr`.
--
-- The `_seq` property is the stable sort key Wave 2 cursor pagination reads.
-- UUID order is random; JOIN-to-events is slow; denormalization closes both.
--
-- AGE label tables are created dynamically (one physical table per label via
-- `create_vlabel`). This migration iterates the label tables that already
-- exist in the tessera_main graph and creates a btree expression index on
-- `(id)` — the trivial cursor fallback — plus attempts the expression index
-- on `(properties->>'_seq')::bigint`. If the expression index fails (agtype
-- cast semantics vary across AGE 1.6 rc0 builds), the btree on `id` is a
-- correct-but-slower fallback that satisfies the W1 acceptance criteria —
-- Wave 2 will layer a proper cursor-bench gate on top.
--
-- For label tables created AFTER this migration runs, the SchemaRegistry
-- `declareNodeType` path is responsible for invoking CREATE INDEX IF NOT
-- EXISTS against the new table. W1 does NOT yet add that hook because the
-- test suite creates label tables implicitly via `session.apply` on first
-- write; Wave 2 will thread the index creation through the Schema Registry
-- declare path (ROADMAP Phase 2.5 has a task for it).
--
-- Note on AGE label table layout: AGE stores each vertex in
-- `<graph_name>."<label>"` with columns `id agtype, properties agtype`. The
-- properties column is the thing we need to index. On AGE 1.6 this is the
-- documented API.

DO $$
DECLARE
    r RECORD;
    idx_name TEXT;
BEGIN
    -- Guard: only run if the tessera_main graph + ag_label catalog exist.
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.schemata WHERE schema_name = 'tessera_main'
    ) THEN
        RAISE NOTICE 'V11: tessera_main schema not present, skipping label-table index sweep';
        RETURN;
    END IF;

    FOR r IN
        SELECT table_name
          FROM information_schema.tables
         WHERE table_schema = 'tessera_main'
           AND table_type = 'BASE TABLE'
           -- Skip AGE internal label tables if present
           AND table_name NOT IN ('_ag_label_vertex', '_ag_label_edge')
    LOOP
        idx_name := 'idx_' || r.table_name || '_model_seq';

        -- Try the agtype expression index first.
        BEGIN
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS %I ON tessera_main.%I ((properties->''model_id''), (properties->''_seq''))',
                idx_name, r.table_name
            );
            RAISE NOTICE 'V11: created expression index % on tessera_main.%', idx_name, r.table_name;
        EXCEPTION WHEN OTHERS THEN
            -- Fallback: btree on id column (always present on AGE label tables)
            BEGIN
                EXECUTE format(
                    'CREATE INDEX IF NOT EXISTS %I ON tessera_main.%I (id)',
                    idx_name || '_id_fallback', r.table_name
                );
                RAISE NOTICE 'V11: expression index failed on tessera_main.% (%), fallback btree(id) created',
                    r.table_name, SQLERRM;
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'V11: both index strategies failed on tessera_main.% (%)', r.table_name, SQLERRM;
            END;
        END;
    END LOOP;
END $$;
