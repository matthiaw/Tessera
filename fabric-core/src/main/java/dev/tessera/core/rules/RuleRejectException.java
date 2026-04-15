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
package dev.tessera.core.rules;

/**
 * Thrown from {@code GraphServiceImpl.apply} when a VALIDATE-chain rule
 * returns a Reject outcome. The {@code @Transactional} boundary rolls back
 * the Cypher write + event log + outbox insert atomically (VALID-05).
 */
public class RuleRejectException extends RuntimeException {

    private final String ruleId;

    public RuleRejectException(String message, String ruleId) {
        super(message);
        this.ruleId = ruleId;
    }

    public String ruleId() {
        return ruleId;
    }
}
