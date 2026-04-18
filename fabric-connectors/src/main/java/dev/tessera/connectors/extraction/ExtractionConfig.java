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
package dev.tessera.connectors.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the LLM extraction pipeline.
 *
 * @param model     the LLM model to use (default: claude-sonnet-4-5)
 * @param maxTokens maximum tokens for LLM response (default: 4096)
 * @param temperature LLM temperature (default: 0.0 for deterministic extraction)
 * @param maxRetries maximum retry attempts on malformed JSON output (default: 3)
 */
@ConfigurationProperties(prefix = "tessera.extraction")
public record ExtractionConfig(String model, Integer maxTokens, Double temperature, Integer maxRetries) {

    /** Default values applied when properties are not set. */
    public ExtractionConfig {
        if (model == null) model = "claude-sonnet-4-5";
        if (maxTokens == null) maxTokens = 4096;
        if (temperature == null) temperature = 0.0;
        if (maxRetries == null) maxRetries = 3;
    }
}
