/*
 * Copyright 2026 Tessera Contributors
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
 */
package dev.tessera.rules;

/**
 * Four named chains in the fixed pipeline order per ADR-7 §RULE-02 and
 * CONTEXT §D-C1. {@link #VALIDATE} runs first; a Reject outcome there aborts
 * the entire mutation before the Cypher write. Per D-A2 no chain produces a
 * "flag for review" outcome — the review queue is a Phase 2.5 pre-funnel
 * layer.
 */
public enum Chain {
    /** Pre-commit business-rule validation — may REJECT. */
    VALIDATE,
    /** Conflict resolution using {@code source_authority} — may MERGE/OVERRIDE. */
    RECONCILE,
    /** Derived-property computation — may ADD. */
    ENRICH,
    /** Downstream consumer routing hints written to {@code graph_outbox.routing_hints}. */
    ROUTE
}
