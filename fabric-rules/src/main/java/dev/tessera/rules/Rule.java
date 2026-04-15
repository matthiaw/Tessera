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
 * The rule contract per ADR-7 §RULE-02. Every rule is a pure function: it
 * inspects {@link RuleContext} and returns a {@link RuleOutcome}. Rules must
 * NOT perform outbound I/O (no HTTP, no new DB queries) — the
 * {@code RuleEngineHygiene} ArchUnit test enforces this structurally.
 *
 * <p>Within a given {@link Chain}, rules are sorted by {@link #priority()}
 * DESC — higher priority runs first. A rule whose {@link #applies(RuleContext)}
 * returns false is skipped silently. A rule returning {@link RuleOutcome.Reject}
 * in the VALIDATE chain short-circuits the entire pipeline.
 *
 * <p>Per ADR-7 §RULE-04 (hybrid Java-classes-plus-DB-activation), rule logic
 * lives here as Spring beans; {@code reconciliation_rules} carries per-tenant
 * enable/disable plus optional {@code priority_override}.
 */
public interface Rule {

    /** Stable identifier used for logging and {@code reconciliation_conflicts.rule_id}. */
    String id();

    /** The chain this rule belongs to. Fixed per instance. */
    Chain chain();

    /** Default priority. DESC sorted — higher runs first. May be overridden per-tenant. */
    int priority();

    /** Predicate: does this rule apply to the given context? */
    boolean applies(RuleContext ctx);

    /** Evaluate the rule and return an outcome. */
    RuleOutcome evaluate(RuleContext ctx);
}
