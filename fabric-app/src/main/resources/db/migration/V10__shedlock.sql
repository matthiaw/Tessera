-- Phase 1 / Wave 2 / 01-W2-03: ShedLock distributed-lock table.
--
-- Required by the Outbox poller (EVENT-05) so that multiple JVMs / restarts
-- never double-process graph_outbox rows. Schema matches ShedLock 5.x
-- JdbcTemplateLockProvider defaults (name/lock_until/locked_at/locked_by).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
