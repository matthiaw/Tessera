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
package dev.tessera.connectors.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository for the {@code extraction_review_queue} table.
 * All queries filter by {@code model_id} for tenant isolation (T-02.5-10).
 */
@Repository
public class ExtractionReviewRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ExtractionReviewRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * List pending (undecided) review queue entries for the given tenant.
     */
    public List<ReviewQueueEntry> findPending(
            UUID modelId, UUID connectorId, String typeSlug, Instant since, int limit) {

        StringBuilder sql = new StringBuilder(
                """
                SELECT * FROM extraction_review_queue
                WHERE model_id = :model_id::uuid AND decision IS NULL
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("model_id", modelId.toString());

        if (connectorId != null) {
            sql.append(" AND connector_id = :connector_id::uuid");
            params.addValue("connector_id", connectorId.toString());
        }
        if (typeSlug != null) {
            sql.append(" AND type_slug = :type_slug");
            params.addValue("type_slug", typeSlug);
        }
        if (since != null) {
            sql.append(" AND created_at >= :since");
            params.addValue("since", Timestamp.from(since));
        }

        sql.append(" ORDER BY created_at ASC LIMIT :limit");
        params.addValue("limit", limit);

        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    /**
     * Find a single entry by ID, scoped to the given tenant.
     * Returns empty if not found or belongs to a different tenant.
     */
    public Optional<ReviewQueueEntry> findById(UUID id, UUID modelId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("id", id.toString());
        params.addValue("model_id", modelId.toString());

        List<ReviewQueueEntry> rows = jdbc.query(
                "SELECT * FROM extraction_review_queue WHERE id = :id::uuid AND model_id = :model_id::uuid",
                params,
                this::mapRow);

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Mark an entry as accepted. Throws {@link NotFoundException} if no undecided
     * row matches.
     */
    public void markAccepted(UUID id, UUID modelId) {
        int updated = jdbc.update(
                """
                UPDATE extraction_review_queue
                SET decision = 'ACCEPTED', decided_at = clock_timestamp()
                WHERE id = :id::uuid AND model_id = :model_id::uuid AND decision IS NULL
                """,
                new MapSqlParameterSource("id", id.toString()).addValue("model_id", modelId.toString()));

        if (updated == 0) {
            throw new NotFoundException("Review queue entry not found or already decided: " + id);
        }
    }

    /**
     * Mark an entry as rejected with a reason.
     */
    public void markRejected(UUID id, UUID modelId, String reason) {
        int updated = jdbc.update(
                """
                UPDATE extraction_review_queue
                SET decision = 'REJECTED', decision_reason = :reason, decided_at = clock_timestamp()
                WHERE id = :id::uuid AND model_id = :model_id::uuid AND decision IS NULL
                """,
                new MapSqlParameterSource("id", id.toString())
                        .addValue("model_id", modelId.toString())
                        .addValue("reason", reason));

        if (updated == 0) {
            throw new NotFoundException("Review queue entry not found or already decided: " + id);
        }
    }

    /**
     * Mark an entry as overridden with the operator-specified target node UUID.
     */
    public void markOverridden(UUID id, UUID modelId, UUID targetNodeUuid) {
        int updated = jdbc.update(
                """
                UPDATE extraction_review_queue
                SET decision = 'OVERRIDDEN', operator_target_node_uuid = :target::uuid, decided_at = clock_timestamp()
                WHERE id = :id::uuid AND model_id = :model_id::uuid AND decision IS NULL
                """,
                new MapSqlParameterSource("id", id.toString())
                        .addValue("model_id", modelId.toString())
                        .addValue("target", targetNodeUuid.toString()));

        if (updated == 0) {
            throw new NotFoundException("Review queue entry not found or already decided: " + id);
        }
    }

    /**
     * Insert a new review queue entry.
     */
    public void insert(ReviewQueueEntry entry) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("id", entry.id().toString());
        params.addValue("model_id", entry.modelId().toString());
        params.addValue("connector_id", entry.connectorId().toString());
        params.addValue("source_document_id", entry.sourceDocumentId());
        params.addValue("source_chunk_range", entry.sourceChunkRange());
        params.addValue("type_slug", entry.typeSlug());
        params.addValue("extracted_properties", toJson(entry.extractedProperties()));
        params.addValue("extraction_confidence", entry.extractionConfidence());
        params.addValue("extractor_version", entry.extractorVersion());
        params.addValue("llm_model_id", entry.llmModelId());
        params.addValue("resolution_tier", entry.resolutionTier());
        params.addValue("resolution_score", entry.resolutionScore());

        jdbc.update(
                """
                INSERT INTO extraction_review_queue
                    (id, model_id, connector_id, source_document_id, source_chunk_range,
                     type_slug, extracted_properties, extraction_confidence, extractor_version,
                     llm_model_id, resolution_tier, resolution_score)
                VALUES
                    (:id::uuid, :model_id::uuid, :connector_id::uuid, :source_document_id,
                     :source_chunk_range, :type_slug, :extracted_properties::jsonb,
                     :extraction_confidence, :extractor_version, :llm_model_id,
                     :resolution_tier, :resolution_score)
                """,
                params);
    }

    private ReviewQueueEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewQueueEntry(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("model_id")),
                UUID.fromString(rs.getString("connector_id")),
                rs.getString("source_document_id"),
                rs.getString("source_chunk_range"),
                rs.getString("type_slug"),
                parseJson(rs.getString("extracted_properties")),
                rs.getBigDecimal("extraction_confidence"),
                rs.getString("extractor_version"),
                rs.getString("llm_model_id"),
                rs.getString("resolution_tier"),
                rs.getBigDecimal("resolution_score"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("decided_at")),
                rs.getString("decision"),
                rs.getString("decision_reason"),
                rs.getString("operator_target_node_uuid") != null
                        ? UUID.fromString(rs.getString("operator_target_node_uuid"))
                        : null);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
