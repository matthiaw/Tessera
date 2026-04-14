-- MIRROR OF fabric-app/src/main/resources/db/migration/V1__enable_age.sql
-- Kept byte-identical so fabric-core's Spring Boot integration tests can exercise
-- the same Flyway baseline without pulling fabric-app onto the test classpath.
-- A plan-04 text-diff/ArchUnit rule will enforce equality. Do NOT edit in isolation.
--
-- Phase 0 baseline migration: enable Apache AGE.
-- D-10: Flyway owns the one-time DDL; HikariCP owns per-session priming.
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT create_graph('tessera_main');
