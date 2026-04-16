-- Phase 0 baseline migration: enable Apache AGE.
-- D-10: Flyway owns the one-time DDL; HikariCP owns per-session priming.
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT create_graph('tessera_main');
