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
package dev.tessera.connectors.unstructured;

import dev.tessera.connectors.CandidateMutation;
import dev.tessera.connectors.Connector;
import dev.tessera.connectors.ConnectorCapabilities;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.DlqEntry;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.connectors.extraction.ExtractionCandidate;
import dev.tessera.connectors.extraction.ExtractionConfig;
import dev.tessera.connectors.extraction.ExtractionException;
import dev.tessera.connectors.extraction.ExtractionService;
import dev.tessera.connectors.extraction.ParagraphChunker;
import dev.tessera.connectors.extraction.SentenceChunker;
import dev.tessera.connectors.extraction.TextChunk;
import dev.tessera.connectors.extraction.TextChunker;
import dev.tessera.core.tenant.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * EXTR-01 / CONTEXT Decision 9: Markdown folder connector.
 * Scans a configured folder for {@code .md} files, detects changes via
 * SHA-256 content hashing, chunks text, extracts entities via
 * {@link ExtractionService}, and produces {@link CandidateMutation}s
 * with full provenance fields (EXTR-04).
 *
 * <p>This class MUST NOT call {@code GraphService} directly -- only
 * {@code ConnectorRunner} may (enforced by ArchUnit).
 *
 * <p>Threat mitigations:
 * <ul>
 *   <li>T-02.5-13: folder_path validated against path traversal (no ".." allowed)</li>
 *   <li>T-02.5-14: files exceeding max size (default 1MB) are skipped with a DLQ entry</li>
 * </ul>
 */
@Component
public class MarkdownFolderConnector implements Connector {

    private static final Logger LOG = LoggerFactory.getLogger(MarkdownFolderConnector.class);
    private static final long DEFAULT_MAX_FILE_SIZE = 1_048_576L; // 1 MB

    private final ExtractionService extractionService;
    private final ExtractionConfig extractionConfig;
    private final String extractorVersion;

    public MarkdownFolderConnector(
            ExtractionService extractionService,
            ExtractionConfig extractionConfig,
            @Value("${tessera.version:0.1.0-SNAPSHOT}") String extractorVersion) {
        this.extractionService = extractionService;
        this.extractionConfig = extractionConfig;
        this.extractorVersion = extractorVersion;
    }

    @Override
    public String type() {
        return "unstructured-text";
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(false);
    }

    @Override
    public PollResult poll(Clock clock, MappingDefinition mapping, ConnectorState state, TenantContext tenant) {
        String folderPath = mapping.folderPath();
        String globPattern = mapping.globPattern() != null ? mapping.globPattern() : "**/*.md";
        String chunkStrategy = mapping.chunkStrategy() != null ? mapping.chunkStrategy() : "paragraph";
        int chunkOverlapChars = mapping.chunkOverlapChars() != null ? mapping.chunkOverlapChars() : 200;
        String provider = mapping.provider() != null ? mapping.provider() : "anthropic";
        String connectorId =
                state.customState() != null ? (String) state.customState().get("connector_id") : "unknown";

        // T-02.5-13: Validate folder path
        if (folderPath == null || folderPath.contains("..")) {
            LOG.error("Invalid folder_path: {}", folderPath);
            return PollResult.failed(state);
        }

        // Only anthropic accepted in Phase 2.5
        if (!"anthropic".equals(provider)) {
            LOG.error("Unsupported provider '{}', only 'anthropic' is accepted", provider);
            return PollResult.failed(state);
        }

        Path folder = Path.of(folderPath);
        if (!Files.isDirectory(folder)) {
            LOG.error("folder_path does not exist or is not a directory: {}", folderPath);
            return PollResult.failed(state);
        }

        TextChunker chunker = resolveChunker(chunkStrategy);

        // Load previous file hashes from state
        @SuppressWarnings("unchecked")
        Map<String, String> previousHashes = state.customState() != null
                ? (Map<String, String>) state.customState().getOrDefault("file_hashes", Map.of())
                : Map.of();

        Map<String, String> newHashes = new HashMap<>(previousHashes);
        List<CandidateMutation> candidates = new ArrayList<>();
        List<DlqEntry> dlq = new ArrayList<>();

        // Scan folder for matching files
        List<Path> matchingFiles = scanFolder(folder, globPattern);

        for (Path file : matchingFiles) {
            try {
                // T-02.5-14: Skip oversized files
                long fileSize = Files.size(file);
                if (fileSize > DEFAULT_MAX_FILE_SIZE) {
                    LOG.warn("Skipping oversized file ({} bytes): {}", fileSize, file);
                    dlq.add(new DlqEntry(
                            "FILE_TOO_LARGE",
                            "File exceeds max size (" + DEFAULT_MAX_FILE_SIZE + " bytes): " + file,
                            Map.of("path", file.toString(), "size", fileSize)));
                    continue;
                }

                String content = Files.readString(file, StandardCharsets.UTF_8);
                String contentHash = sha256(content);
                String relativePath = folder.relativize(file).toString();

                // Skip unchanged files
                if (contentHash.equals(previousHashes.get(relativePath))) {
                    newHashes.put(relativePath, contentHash);
                    continue;
                }

                newHashes.put(relativePath, contentHash);

                // Chunk and extract
                List<TextChunk> chunks = chunker.chunk(content, chunkOverlapChars);
                long mtime = Files.getLastModifiedTime(file).toMillis();
                String sourceDocumentId = sha256(connectorId + ":" + relativePath + ":" + mtime);

                for (TextChunk chunk : chunks) {
                    try {
                        List<ExtractionCandidate> extracted = extractionService.extract(chunk, tenant);
                        for (ExtractionCandidate ec : extracted) {
                            String changeId = sha256(
                                    sourceDocumentId + ":" + ec.typeSlug() + ":" + ec.name());
                            String sourceChunkRange = chunk.charOffset() + ":" + chunk.charLength();

                            Map<String, Object> properties = new LinkedHashMap<>();
                            properties.put("name", ec.name());
                            if (ec.properties() != null) {
                                properties.putAll(ec.properties());
                            }

                            candidates.add(new CandidateMutation(
                                    ec.typeSlug(),
                                    null, // targetNodeUuid -- resolution happens in ConnectorRunner
                                    properties,
                                    "unstructured-text:" + connectorId,
                                    connectorId,
                                    changeId,
                                    sourceDocumentId,
                                    sourceChunkRange,
                                    extractorVersion,
                                    extractionConfig.model(),
                                    ec.confidence()));
                        }
                    } catch (ExtractionException e) {
                        LOG.warn("Extraction failed for chunk {} of {}: {}", chunk.chunkIndex(), relativePath,
                                e.getMessage());
                        dlq.add(new DlqEntry(
                                "EXTRACTION_ERROR",
                                "Extraction failed for chunk " + chunk.chunkIndex() + " of " + relativePath + ": "
                                        + e.getMessage(),
                                Map.of("file", relativePath, "chunkIndex", chunk.chunkIndex())));
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to read file {}: {}", file, e.getMessage());
                dlq.add(new DlqEntry(
                        "IO_ERROR",
                        "Failed to read file: " + e.getMessage(),
                        Map.of("path", file.toString())));
            }
        }

        // Build outcome
        SyncOutcome outcome;
        if (!dlq.isEmpty() && candidates.isEmpty()) {
            outcome = SyncOutcome.FAILED;
        } else if (!dlq.isEmpty()) {
            outcome = SyncOutcome.PARTIAL;
        } else {
            outcome = SyncOutcome.SUCCESS;
        }

        // Build next state with updated file hashes
        Map<String, Object> nextCustomState = new HashMap<>();
        if (state.customState() != null) {
            nextCustomState.putAll(state.customState());
        }
        nextCustomState.put("file_hashes", newHashes);

        ConnectorState nextState = new ConnectorState(
                null, null, null, state.lastSequence(), nextCustomState);

        return new PollResult(candidates, nextState, outcome, dlq);
    }

    private TextChunker resolveChunker(String strategy) {
        return switch (strategy) {
            case "sentence" -> new SentenceChunker();
            case "paragraph" -> new ParagraphChunker();
            default -> new ParagraphChunker();
        };
    }

    private List<Path> scanFolder(Path folder, String globPattern) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
        List<Path> result = new ArrayList<>();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = folder.relativize(file);
                    if (matcher.matches(relative)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Failed to scan folder {}: {}", folder, e.getMessage());
        }
        return result;
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
