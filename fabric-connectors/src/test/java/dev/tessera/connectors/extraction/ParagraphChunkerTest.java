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

class ParagraphChunkerTest {

    private final ParagraphChunker chunker = new ParagraphChunker();

    @Test
    void singleParagraph_producesOneChunk() {
        String text = "This is a single paragraph with some content.";
        List<TextChunk> chunks = chunker.chunk(text, 0);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text);
        assertThat(chunks.get(0).charOffset()).isEqualTo(0);
        assertThat(chunks.get(0).charLength()).isEqualTo(text.length());
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
    }

    @Test
    void twoParagraphs_produceTwoChunksWithCorrectOffsets() {
        String para1 = "First paragraph here.";
        String para2 = "Second paragraph here.";
        String text = para1 + "\n\n" + para2;

        List<TextChunk> chunks = chunker.chunk(text, 0);

        assertThat(chunks).hasSize(2);

        assertThat(chunks.get(0).text()).isEqualTo(para1);
        assertThat(chunks.get(0).charOffset()).isEqualTo(0);
        assertThat(chunks.get(0).charLength()).isEqualTo(para1.length());
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);

        assertThat(chunks.get(1).text()).isEqualTo(para2);
        assertThat(chunks.get(1).charOffset()).isEqualTo(para1.length() + 2); // +2 for \n\n
        assertThat(chunks.get(1).charLength()).isEqualTo(para2.length());
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    void overlapPrependsLastNCharsFromPreviousChunk() {
        String para1 = "First paragraph with enough text to have overlap applied.";
        String para2 = "Second paragraph here.";
        String text = para1 + "\n\n" + para2;
        int overlap = 10;

        List<TextChunk> chunks = chunker.chunk(text, overlap);

        assertThat(chunks).hasSize(2);
        // First chunk has no overlap
        assertThat(chunks.get(0).text()).isEqualTo(para1);

        // Second chunk text starts with last 10 chars of first paragraph
        String expectedOverlap = para1.substring(para1.length() - overlap);
        assertThat(chunks.get(1).text()).startsWith(expectedOverlap);
        assertThat(chunks.get(1).text()).endsWith(para2);
        // charOffset still points to original position (not overlap position)
        assertThat(chunks.get(1).charOffset()).isEqualTo(para1.length() + 2);
        // charLength is of the original paragraph, not including overlap
        assertThat(chunks.get(1).charLength()).isEqualTo(para2.length());
    }

    @Test
    void emptyText_returnsEmptyList() {
        List<TextChunk> chunks = chunker.chunk("", 0);
        assertThat(chunks).isEmpty();
    }

    @Test
    void whitespaceOnlyText_returnsEmptyList() {
        List<TextChunk> chunks = chunker.chunk("   \n\n   \n\n   ", 0);
        assertThat(chunks).isEmpty();
    }

    @Test
    void determinism_sameInputProducesIdenticalChunks() {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";
        List<TextChunk> first = chunker.chunk(text, 50);
        List<TextChunk> second = chunker.chunk(text, 50);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void multipleNewlines_treatedAsSingleSeparator() {
        String para1 = "First paragraph.";
        String para2 = "Second paragraph.";
        String text = para1 + "\n\n\n\n" + para2;

        List<TextChunk> chunks = chunker.chunk(text, 0);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(para1);
        assertThat(chunks.get(1).text()).isEqualTo(para2);
    }
}
