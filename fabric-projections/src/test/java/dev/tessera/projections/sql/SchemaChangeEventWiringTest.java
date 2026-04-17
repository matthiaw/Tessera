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
package dev.tessera.projections.sql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import dev.tessera.core.schema.SchemaChangeEvent;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.schema.SchemaVersionService;
import dev.tessera.core.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Unit tests verifying that SqlViewProjection.onSchemaChange() calls regenerateForTenant
 * with the correct TenantContext and handles exceptions without rethrowing.
 */
class SchemaChangeEventWiringTest {

    private static final UUID MODEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private SchemaRegistry schemaRegistry;
    private SchemaVersionService schemaVersionService;
    private NamedParameterJdbcTemplate jdbc;
    private SqlViewProjection projection;

    @BeforeEach
    void setUp() {
        schemaRegistry = mock(SchemaRegistry.class);
        schemaVersionService = mock(SchemaVersionService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        projection = spy(new SqlViewProjection(schemaRegistry, schemaVersionService, jdbc));
    }

    @Test
    void onSchemaChange_callsRegenerateForTenantWithCorrectContext() {
        // Arrange
        SchemaChangeEvent event = new SchemaChangeEvent(MODEL_ID, "ADD_PROPERTY", "person");

        // Act
        projection.onSchemaChange(event);

        // Assert — regenerateForTenant must be called with TenantContext matching the event's modelId
        verify(projection).regenerateForTenant(TenantContext.of(MODEL_ID));
    }

    @Test
    void onSchemaChange_doesNotPropagateExceptionsFromRegenerateForTenant() {
        // Arrange — make regenerateForTenant throw an exception
        SchemaChangeEvent event = new SchemaChangeEvent(MODEL_ID, "CREATE_TYPE", "widget");
        doThrow(new RuntimeException("DB error during regeneration"))
                .when(projection)
                .regenerateForTenant(any(TenantContext.class));

        // Act — must not throw
        projection.onSchemaChange(event);

        // No assertion needed — the test passes if no exception propagates
    }
}
