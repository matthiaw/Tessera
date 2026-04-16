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

import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Wraps Spring AI {@link ChatModel} with retry logic and structured output
 * conversion for LLM-based entity extraction from unstructured text.
 *
 * <p>Per AI-SPEC Section 4: temperature 0.0 for deterministic extraction,
 * maxTokens 4096, exponential backoff retry on malformed JSON output.
 * The JSON schema is derived from the Schema Registry at call time
 * (EXTR-03, CONTEXT Decision 2).
 *
 * <p>Threat mitigation: T-02.5-05 -- JSON is parsed strictly via Jackson
 * ObjectMapper; no eval() or code execution from LLM output. T-02.5-06 --
 * retries capped at 3 with exponential backoff (max 7s total).
 */
@Service
@EnableConfigurationProperties(ExtractionConfig.class)
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private static final String SYSTEM_PROMPT =
            """
            You are a precise entity extraction system. Extract all entities and relationships
            from the provided text according to the given schema.

            Rules:
            - Do NOT invent entities not present in the text.
            - Do NOT hallucinate properties or relationships.
            - For each entity, include the exact character offset where it appears in the source text.
            - Assign a confidence score between 0.0 and 1.0 for each extracted entity.
            - Return ONLY valid JSON matching the requested format.
            - If no entities are found, return an empty array [].
            """;

    private static final String USER_PROMPT_TEMPLATE =
            """
            Extract all entities and relationships from the following text.
            Return ONLY valid JSON matching the schema below.

            Text: {text}

            {format}
            """;

    private final ChatModel chatModel;
    private final SchemaRegistrySchemaBuilder schemaBuilder;
    private final ExtractionConfig config;

    public ExtractionService(
            ChatModel chatModel, SchemaRegistrySchemaBuilder schemaBuilder, ExtractionConfig config) {
        this.chatModel = chatModel;
        this.schemaBuilder = schemaBuilder;
        this.config = config;
    }

    /**
     * Extract entities from a text chunk using the LLM.
     *
     * @param chunk  the text chunk to extract from
     * @param tenant the tenant context (determines which schema types to extract)
     * @return list of extraction candidates
     * @throws ExtractionException if extraction fails after all retries
     */
    public List<ExtractionCandidate> extract(TextChunk chunk, TenantContext tenant) {
        String schemaJson = schemaBuilder.buildExtractionSchema(tenant);
        DynamicSchemaOutputConverter converter = new DynamicSchemaOutputConverter(schemaJson);

        Exception lastException = null;

        for (int attempt = 0; attempt < config.maxRetries(); attempt++) {
            try {
                String response = callLlm(chunk.text(), converter.getFormat());
                return converter.convert(response);
            } catch (ExtractionException e) {
                lastException = e;
                log.warn(
                        "Extraction attempt {}/{} failed for chunk at offset {}: {}",
                        attempt + 1,
                        config.maxRetries(),
                        chunk.charOffset(),
                        e.getMessage());

                if (attempt < config.maxRetries() - 1) {
                    // Exponential backoff: 1s, 2s, 4s (safe on virtual threads)
                    long backoffMs = 1000L * (1L << attempt);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ExtractionException("Extraction interrupted during backoff", ie);
                    }
                }
            }
        }

        throw new ExtractionException(
                "LLM extraction failed after " + config.maxRetries() + " attempts", lastException);
    }

    private String callLlm(String text, String formatInstructions) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .temperature(config.temperature())
                .build();

        Prompt prompt = new Prompt(
                List.of(
                        new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(),
                        new org.springframework.ai.chat.messages.UserMessage(
                                USER_PROMPT_TEMPLATE
                                        .replace("{text}", text)
                                        .replace("{format}", formatInstructions))),
                options);

        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
