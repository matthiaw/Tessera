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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sentence-based text chunker using {@link BreakIterator} for boundary detection.
 * Handles abbreviations (e.g., "Dr.", "Mr.") correctly via the ICU sentence rules
 * in the JDK. Produces deterministic output for identical inputs.
 */
public class SentenceChunker implements TextChunker {

    @Override
    public List<TextChunk> chunk(String text, int overlapChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        bi.setText(text);

        List<TextChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        String previousChunkText = null;

        int start = bi.first();
        int end = bi.next();

        while (end != BreakIterator.DONE) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) {
                String chunkText = sentence;
                if (overlapChars > 0 && previousChunkText != null) {
                    int overlapStart = Math.max(0, previousChunkText.length() - overlapChars);
                    String overlap = previousChunkText.substring(overlapStart);
                    chunkText = overlap + sentence;
                }
                chunks.add(new TextChunk(chunkText, start, sentence.length(), chunkIndex));
                previousChunkText = sentence;
                chunkIndex++;
            }
            start = end;
            end = bi.next();
        }

        return chunks;
    }
}
