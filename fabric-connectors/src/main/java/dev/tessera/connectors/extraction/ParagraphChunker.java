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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default text chunker. Splits on two or more consecutive newlines (paragraph boundaries).
 * Produces deterministic output for identical inputs. Empty/whitespace-only paragraphs
 * are skipped.
 */
public class ParagraphChunker implements TextChunker {

    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n{2,}");

    @Override
    public List<TextChunk> chunk(String text, int overlapChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        Matcher matcher = PARAGRAPH_SEPARATOR.matcher(text);

        int start = 0;
        int chunkIndex = 0;
        String previousChunkText = null;

        while (matcher.find()) {
            String paragraph = text.substring(start, matcher.start());
            if (!paragraph.isBlank()) {
                chunks.add(buildChunk(paragraph, start, chunkIndex, overlapChars, previousChunkText));
                previousChunkText = paragraph;
                chunkIndex++;
            }
            start = matcher.end();
        }

        // Last paragraph (after final separator or the entire text if no separator)
        if (start < text.length()) {
            String paragraph = text.substring(start);
            if (!paragraph.isBlank()) {
                chunks.add(buildChunk(paragraph, start, chunkIndex, overlapChars, previousChunkText));
            }
        }

        return chunks;
    }

    private TextChunk buildChunk(
            String paragraph, int charOffset, int chunkIndex, int overlapChars, String previousChunkText) {
        String chunkText = paragraph;
        if (overlapChars > 0 && previousChunkText != null) {
            int overlapStart = Math.max(0, previousChunkText.length() - overlapChars);
            String overlap = previousChunkText.substring(overlapStart);
            chunkText = overlap + paragraph;
        }
        return new TextChunk(chunkText, charOffset, paragraph.length(), chunkIndex);
    }
}
