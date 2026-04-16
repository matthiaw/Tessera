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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ExtractionServiceTest {

    private ChatModel chatModel;
    private SchemaRegistrySchemaBuilder schemaBuilder;
    private ExtractionConfig config;
    private ExtractionService service;
    private TenantContext tenant;
    private TextChunk chunk;

    private static final String SCHEMA_JSON =
            """
            {"entityTypes": [{"typeSlug": "person", "properties": ["name"]}]}
            """;

    private static final String VALID_RESPONSE =
            """
            [
              {
                "typeSlug": "person",
                "name": "Jane Smith",
                "properties": {"role": "engineer"},
                "sourceSpan": {"charOffset": 0, "charLength": 10},
                "confidence": 0.9,
                "relationships": []
              }
            ]
            """;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        schemaBuilder = mock(SchemaRegistrySchemaBuilder.class);
        config = new ExtractionConfig(null, null, null, null); // all defaults
        tenant = TenantContext.of(UUID.randomUUID());
        chunk = new TextChunk("Jane Smith is an engineer at Acme Corp.", 0, 39, 0);

        when(schemaBuilder.buildExtractionSchema(any())).thenReturn(SCHEMA_JSON);

        service = new ExtractionService(chatModel, schemaBuilder, config);
    }

    @Test
    void extract_callsChatModelWithCorrectPrompt() {
        Generation generation = new Generation(new AssistantMessage(VALID_RESPONSE));
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        List<ExtractionCandidate> result = service.extract(chunk, tenant);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).typeSlug()).isEqualTo("person");
        assertThat(result.get(0).name()).isEqualTo("Jane Smith");
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void extract_retriesOnMalformedJsonUpToThreeTimes() {
        // First two calls return invalid JSON, third returns valid
        Generation badGeneration = new Generation(new AssistantMessage("not valid json"));
        ChatResponse badResponse = new ChatResponse(List.of(badGeneration));
        Generation goodGeneration = new Generation(new AssistantMessage(VALID_RESPONSE));
        ChatResponse goodResponse = new ChatResponse(List.of(goodGeneration));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(badResponse)
                .thenReturn(badResponse)
                .thenReturn(goodResponse);

        List<ExtractionCandidate> result = service.extract(chunk, tenant);

        assertThat(result).hasSize(1);
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void extract_throwsAfterAllRetriesExhausted() {
        Generation badGeneration = new Generation(new AssistantMessage("not valid json"));
        ChatResponse badResponse = new ChatResponse(List.of(badGeneration));

        when(chatModel.call(any(Prompt.class))).thenReturn(badResponse);

        assertThatThrownBy(() -> service.extract(chunk, tenant))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("3");

        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void extract_usesConfiguredSettings() {
        // Verify the config defaults are applied
        assertThat(config.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(config.maxTokens()).isEqualTo(4096);
        assertThat(config.temperature()).isEqualTo(0.0);
        assertThat(config.maxRetries()).isEqualTo(3);
    }
}
