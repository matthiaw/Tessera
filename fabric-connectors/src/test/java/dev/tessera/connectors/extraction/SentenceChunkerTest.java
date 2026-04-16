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

import java.util.List;
import org.junit.jupiter.api.Test;

class SentenceChunkerTest {

    private final SentenceChunker chunker = new SentenceChunker();

    @Test
    void threeSentences_produceThreeChunks() {
        String text = "First sentence. Second sentence. Third sentence.";
        List<TextChunk> chunks = chunker.chunk(text, 0);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text().trim()).isEqualTo("First sentence.");
        assertThat(chunks.get(1).text().trim()).isEqualTo("Second sentence.");
        assertThat(chunks.get(2).text().trim()).isEqualTo("Third sentence.");
    }

    @Test
    void abbreviation_handledByBreakIterator() {
        // BreakIterator behavior on abbreviations varies by JDK version.
        // JDK 23 splits on "Dr." — this test verifies that all chunks have
        // valid offsets and content regardless of how the JDK splits.
        String text = "Dr. Smith went to the store. He bought apples.";
        List<TextChunk> chunks = chunker.chunk(text, 0);

        // Verify all chunks are non-empty and offsets reconstruct the original text
        assertThat(chunks).isNotEmpty();
        for (TextChunk chunk : chunks) {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.charOffset()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.charLength()).isGreaterThan(0);
            // Verify the chunk text at the claimed offset matches the source
            String expected = text.substring(chunk.charOffset(), chunk.charOffset() + chunk.charLength());
            assertThat(chunk.text().trim()).isEqualTo(expected.trim());
        }

        // Verify sentence ending on "." IS detected as a boundary
        String lastChunk = chunks.get(chunks.size() - 1).text().trim();
        assertThat(lastChunk).endsWith("apples.");
    }

    @Test
    void overlapAppliesBetweenSentences() {
        String text = "First sentence here. Second sentence here.";
        List<TextChunk> chunks = chunker.chunk(text, 5);

        assertThat(chunks).hasSize(2);
        // Second chunk should have overlap from end of first chunk
        String firstChunkText = chunks.get(0).text();
        String expectedOverlap = firstChunkText.substring(firstChunkText.length() - 5);
        assertThat(chunks.get(1).text()).startsWith(expectedOverlap);
    }

    @Test
    void emptyText_returnsEmptyList() {
        List<TextChunk> chunks = chunker.chunk("", 0);
        assertThat(chunks).isEmpty();
    }
}
