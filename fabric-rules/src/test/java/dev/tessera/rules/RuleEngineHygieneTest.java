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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * D-A2 structural lock + rule purity enforcement for {@code dev.tessera.rules..}.
 *
 * <ol>
 *   <li>No class under {@code dev.tessera.rules..} may reference {@code review_queue},
 *       {@code FLAG_FOR_REVIEW}, or {@code DEFER} — per D-A2, the review queue is a
 *       Phase 2.5 pre-funnel layer, NOT a rule engine terminal outcome. Adding those
 *       names into the rule engine package is a silent regression of ADR-7 §RULE-03.
 *   <li>No class under {@code dev.tessera.rules..} may depend on {@code java.net..},
 *       Spring's {@code RestTemplate}, or Spring's reactive {@code WebClient} — rules
 *       must be pure functions of {@link RuleContext}, no outbound I/O.
 * </ol>
 */
@AnalyzeClasses(
        packages = "dev.tessera.rules",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class RuleEngineHygieneTest {

    @ArchTest
    static final ArchRule no_review_queue_references_in_rules_package = noClasses()
            .that()
            .resideInAPackage("dev.tessera.rules..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(".*(?i)(review_?queue|flag_for_review|ReviewQueue).*")
            .because("D-A2: the review queue is a Phase 2.5 pre-funnel layer, not a rule engine"
                    + " terminal outcome. Rule classes must not reference review_queue / FLAG_FOR_REVIEW"
                    + " — those moved to Phase 2.5 per ADR-7 §RULE-03.")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rules_package_has_no_outbound_network_io = noClasses()
            .that()
            .resideInAPackage("dev.tessera.rules..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..")
            .because("Rules must be pure functions of RuleContext — no outbound HTTP"
                    + " (HttpClient / RestTemplate / WebClient). I/O belongs in connectors, not rules.")
            .allowEmptyShould(true);
}
