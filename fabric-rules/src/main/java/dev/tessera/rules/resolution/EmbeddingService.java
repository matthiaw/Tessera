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
package dev.tessera.rules.resolution;

import java.util.List;
import java.util.UUID;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates embeddings via Spring AI {@link EmbeddingModel} and stores/queries
 * them in the {@code entity_embeddings} table using pgvector.
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final NamedParameterJdbcTemplate jdbc;

    public EmbeddingService(EmbeddingModel embeddingModel, NamedParameterJdbcTemplate jdbc) {
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
    }

    /**
     * Generate an embedding vector for the given text.
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /**
     * Store or update an embedding for a graph node.
     * Uses {@code ON CONFLICT ... DO UPDATE} for upsert semantics.
     */
    public void store(UUID nodeUuid, UUID modelId, String embeddingModelName, float[] embedding) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("node_uuid", nodeUuid.toString());
        params.addValue("model_id", modelId.toString());
        params.addValue("embedding_model", embeddingModelName);
        params.addValue("embedding", toVectorLiteral(embedding));

        jdbc.update(
                """
                INSERT INTO entity_embeddings (node_uuid, model_id, embedding_model, embedding)
                VALUES (:node_uuid::uuid, :model_id::uuid, :embedding_model, :embedding::vector)
                ON CONFLICT (node_uuid, model_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embedding_model = EXCLUDED.embedding_model,
                    updated_at = clock_timestamp()
                """,
                params);
    }

    /**
     * Find entities similar to the given query embedding using pgvector cosine distance.
     *
     * @return list of similar entities ordered by similarity descending
     */
    public List<SimilarEntity> findSimilar(UUID modelId, String embeddingModelName, float[] queryEmbedding, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("model_id", modelId.toString());
        params.addValue("embedding_model", embeddingModelName);
        params.addValue("query", toVectorLiteral(queryEmbedding));
        params.addValue("limit", limit);

        return jdbc.query(
                """
                SELECT node_uuid, 1 - (embedding <=> :query::vector) AS similarity
                FROM entity_embeddings
                WHERE model_id = :model_id::uuid AND embedding_model = :embedding_model
                ORDER BY embedding <=> :query::vector
                LIMIT :limit
                """,
                params,
                (rs, rowNum) -> new SimilarEntity(
                        UUID.fromString(rs.getString("node_uuid")),
                        rs.getDouble("similarity")));
    }

    /**
     * A graph node and its cosine similarity score to the query embedding.
     */
    public record SimilarEntity(UUID nodeUuid, double similarity) {}

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
