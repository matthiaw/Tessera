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
package dev.tessera.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tessera.core.schema.internal.SchemaChangeReplayer;
import dev.tessera.core.schema.internal.SchemaDescriptorCache;
import dev.tessera.core.schema.internal.SchemaRepository;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests verifying that SchemaRegistry publishes SchemaChangeEvent from all 8 mutating methods
 * with correct modelId, changeType, and typeSlug.
 *
 * <p>Note: SchemaVersionService.applyChange() returns long, so we use when().thenReturn() not
 * doNothing().
 */
class SchemaRegistryEventPublishingTest {

    private static final UUID MODEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final TenantContext CTX = TenantContext.of(MODEL_ID);

    private SchemaRepository repo;
    private SchemaVersionService versions;
    private SchemaAliasService aliases;
    private SchemaDescriptorCache cache;
    private SchemaChangeReplayer replayer;
    private ApplicationEventPublisher publisher;
    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        repo = mock(SchemaRepository.class);
        versions = mock(SchemaVersionService.class);
        aliases = mock(SchemaAliasService.class);
        cache = mock(SchemaDescriptorCache.class);
        replayer = mock(SchemaChangeReplayer.class);
        publisher = mock(ApplicationEventPublisher.class);
        registry = new SchemaRegistry(repo, versions, aliases, cache, replayer, publisher);
        // applyChange returns long — default stub returns 0L which is fine for all tests
        when(versions.applyChange(eq(CTX), anyString(), anyString(), anyString()))
                .thenReturn(0L);
    }

    @Test
    void createNodeType_publishesSchemaChangeEvent() {
        // Arrange
        CreateNodeTypeSpec spec = new CreateNodeTypeSpec("person", "Person", "person", "test node type");
        doNothing().when(repo).insertNodeType(eq(CTX), eq(spec));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));
        when(versions.currentVersion(eq(CTX))).thenReturn(1L);
        NodeTypeDescriptor descriptor =
                new NodeTypeDescriptor(MODEL_ID, "person", "Person", "person", "test", 1L, List.of(), null);
        when(repo.findNodeType(eq(CTX), eq("person"), eq(1L))).thenReturn(Optional.of(descriptor));

        // Act
        registry.createNodeType(CTX, spec);

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("CREATE_TYPE");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void updateNodeTypeDescription_publishesSchemaChangeEvent() {
        // Arrange
        doNothing().when(repo).updateNodeTypeDescription(eq(CTX), eq("person"), anyString());
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));

        // Act
        registry.updateNodeTypeDescription(CTX, "person", "Updated description");

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("UPDATE_TYPE");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void deprecateNodeType_publishesSchemaChangeEvent() {
        // Arrange
        doNothing().when(repo).deprecateNodeType(eq(CTX), eq("person"));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));

        // Act
        registry.deprecateNodeType(CTX, "person");

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("DEPRECATE_TYPE");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void addProperty_publishesSchemaChangeEvent() {
        // Arrange
        AddPropertySpec spec = new AddPropertySpec("color", "Color", "STRING", false);
        doNothing().when(repo).insertProperty(eq(CTX), eq("person"), eq(spec));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));
        PropertyDescriptor prop =
                new PropertyDescriptor("color", "Color", "STRING", false, null, null, null, null, null);
        when(repo.listProperties(eq(CTX), eq("person"))).thenReturn(List.of(prop));

        // Act
        registry.addProperty(CTX, "person", spec);

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("ADD_PROPERTY");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void deprecateProperty_publishesSchemaChangeEvent() {
        // Arrange
        doNothing().when(repo).deprecateProperty(eq(CTX), eq("person"), eq("color"));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));

        // Act
        registry.deprecateProperty(CTX, "person", "color");

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("DEPRECATE_PROPERTY");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void createEdgeType_publishesSchemaChangeEvent() {
        // Arrange
        CreateEdgeTypeSpec spec = new CreateEdgeTypeSpec("knows", "Knows", "KNOWS", "person", "person", "MANY_TO_MANY");
        doNothing().when(repo).insertEdgeType(eq(CTX), eq(spec));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));
        EdgeTypeDescriptor edgeDesc =
                new EdgeTypeDescriptor(MODEL_ID, "knows", "Knows", "KNOWS", "person", "person", "MANY_TO_MANY", null);
        when(repo.findEdgeType(eq(CTX), eq("knows"))).thenReturn(Optional.of(edgeDesc));

        // Act
        registry.createEdgeType(CTX, spec);

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("CREATE_EDGE_TYPE");
        assertThat(event.typeSlug()).isEqualTo("knows");
    }

    @Test
    void renameProperty_publishesSchemaChangeEvent() {
        // Arrange
        doNothing().when(aliases).recordPropertyAlias(eq(CTX), eq("person"), eq("colour"), eq("color"));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));

        // Act
        registry.renameProperty(CTX, "person", "colour", "color");

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("RENAME_PROPERTY");
        assertThat(event.typeSlug()).isEqualTo("person");
    }

    @Test
    void removeRequiredPropertyOrReject_publishesSchemaChangeEvent() {
        // Arrange — non-required property, force=false (no exception thrown)
        PropertyDescriptor nonRequiredProp =
                new PropertyDescriptor("color", "Color", "STRING", false, null, null, null, null, null);
        when(repo.listProperties(eq(CTX), eq("person"))).thenReturn(List.of(nonRequiredProp));
        doNothing().when(repo).deleteProperty(eq(CTX), eq("person"), eq("color"));
        doNothing().when(cache).invalidateAll(eq(MODEL_ID));

        // Act
        registry.removeRequiredPropertyOrReject(CTX, "person", "color", false);

        // Assert
        ArgumentCaptor<SchemaChangeEvent> captor = ArgumentCaptor.forClass(SchemaChangeEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SchemaChangeEvent event = captor.getValue();
        assertThat(event.modelId()).isEqualTo(MODEL_ID);
        assertThat(event.changeType()).isEqualTo("REMOVE_PROPERTY");
        assertThat(event.typeSlug()).isEqualTo("person");
    }
}
