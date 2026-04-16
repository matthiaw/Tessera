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

import dev.tessera.core.schema.internal.SchemaChangeReplayer;
import dev.tessera.core.schema.internal.SchemaDescriptorCache;
import dev.tessera.core.schema.internal.SchemaDescriptorCache.DescriptorKey;
import dev.tessera.core.schema.internal.SchemaRepository;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCHEMA-01..08 facade. Source of truth for schema metadata across Tessera.
 *
 * <p>Consumers in Phase 2+ (Projection Engine, MCP tool dispatch, Wave 3
 * SHACL shape compilation) call {@link #loadFor(TenantContext, String)} to
 * get a Caffeine-cached {@link NodeTypeDescriptor}. Every mutation writes a
 * {@code schema_change_event} row (D-B2) and materializes a new
 * {@code schema_version} snapshot; the breaking-change detector
 * ({@link #applyChangeOrReject}) enforces SCHEMA-08 unless an explicit
 * {@code force} flag is passed.
 *
 * <p>See also {@link SchemaBreakingChangeException}.
 */
@Service
public class SchemaRegistry {

    private final SchemaRepository repo;
    private final SchemaVersionService versions;
    private final SchemaAliasService aliases;
    private final SchemaDescriptorCache cache;
    private final SchemaChangeReplayer replayer;

    public SchemaRegistry(
            SchemaRepository repo,
            SchemaVersionService versions,
            SchemaAliasService aliases,
            SchemaDescriptorCache cache,
            SchemaChangeReplayer replayer) {
        this.repo = repo;
        this.versions = versions;
        this.aliases = aliases;
        this.cache = cache;
        this.replayer = replayer;
    }

    // ---------- node types (SCHEMA-01) ----------

    @Transactional(propagation = Propagation.REQUIRED)
    public NodeTypeDescriptor createNodeType(TenantContext ctx, CreateNodeTypeSpec spec) {
        repo.insertNodeType(ctx, spec);
        String payload = "{\"changeType\":\"CREATE_TYPE\",\"typeSlug\":\"" + spec.slug() + "\"}";
        versions.applyChange(ctx, "CREATE_TYPE", payload, "schema-registry");
        cache.invalidateAll(ctx.modelId());
        return repo.findNodeType(ctx, spec.slug(), versions.currentVersion(ctx))
                .orElseThrow(() -> new IllegalStateException("createNodeType: inserted but not found"));
    }

    public List<NodeTypeDescriptor> listNodeTypes(TenantContext ctx) {
        return repo.listNodeTypes(ctx, versions.currentVersion(ctx));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void updateNodeTypeDescription(TenantContext ctx, String typeSlug, String description) {
        repo.updateNodeTypeDescription(ctx, typeSlug, description);
        versions.applyChange(
                ctx,
                "UPDATE_TYPE",
                "{\"changeType\":\"UPDATE_TYPE\",\"typeSlug\":\"" + typeSlug + "\"}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deprecateNodeType(TenantContext ctx, String typeSlug) {
        repo.deprecateNodeType(ctx, typeSlug);
        versions.applyChange(
                ctx,
                "DEPRECATE_TYPE",
                "{\"changeType\":\"DEPRECATE_TYPE\",\"typeSlug\":\"" + typeSlug + "\"}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
    }

    // ---------- properties (SCHEMA-02) ----------

    @Transactional(propagation = Propagation.REQUIRED)
    public PropertyDescriptor addProperty(TenantContext ctx, String typeSlug, AddPropertySpec spec) {
        repo.insertProperty(ctx, typeSlug, spec);
        String payload = "{\"changeType\":\"ADD_PROPERTY\",\"typeSlug\":\"" + typeSlug + "\",\"propertySlug\":\""
                + spec.slug() + "\",\"dataType\":\"" + spec.dataType() + "\",\"required\":" + spec.required() + "}";
        versions.applyChange(ctx, "ADD_PROPERTY", payload, "schema-registry");
        cache.invalidateAll(ctx.modelId());
        return repo.listProperties(ctx, typeSlug).stream()
                .filter(p -> p.slug().equals(spec.slug()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("addProperty: inserted but not found"));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deprecateProperty(TenantContext ctx, String typeSlug, String propertySlug) {
        repo.deprecateProperty(ctx, typeSlug, propertySlug);
        versions.applyChange(
                ctx,
                "DEPRECATE_PROPERTY",
                "{\"changeType\":\"DEPRECATE_PROPERTY\",\"typeSlug\":\"" + typeSlug + "\",\"propertySlug\":\""
                        + propertySlug + "\"}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
    }

    // ---------- edge types (SCHEMA-03) ----------

    @Transactional(propagation = Propagation.REQUIRED)
    public EdgeTypeDescriptor createEdgeType(TenantContext ctx, CreateEdgeTypeSpec spec) {
        repo.insertEdgeType(ctx, spec);
        versions.applyChange(
                ctx,
                "CREATE_EDGE_TYPE",
                "{\"changeType\":\"CREATE_EDGE_TYPE\",\"slug\":\"" + spec.slug() + "\"}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
        return repo.findEdgeType(ctx, spec.slug())
                .orElseThrow(() -> new IllegalStateException("createEdgeType: inserted but not found"));
    }

    public Optional<EdgeTypeDescriptor> findEdgeType(TenantContext ctx, String slug) {
        return repo.findEdgeType(ctx, slug);
    }

    // ---------- cached lookup (SCHEMA-06, SCHEMA-07) ----------

    /**
     * Caffeine-cached lookup. Resolves alias slugs via
     * {@link SchemaAliasService}. Returns {@link Optional#empty()} if the
     * type is not registered — callers may permit unregistered types during
     * bootstrap; Wave 3 SHACL validation is where unregistered types are
     * rejected.
     */
    public Optional<NodeTypeDescriptor> loadFor(TenantContext ctx, String typeSlug) {
        long version = versions.currentVersion(ctx);
        DescriptorKey key = new DescriptorKey(ctx.modelId(), typeSlug, version);
        NodeTypeDescriptor cached = cache.get(key, k -> {
            Optional<NodeTypeDescriptor> direct = repo.findNodeType(ctx, typeSlug, version);
            if (direct.isPresent()) {
                return direct.get();
            }
            // Try alias resolution — callers pass the old slug; translate to current.
            Optional<String> resolved = aliases.resolveCurrentPropertySlug(ctx, typeSlug, typeSlug);
            if (resolved.isPresent()) {
                return repo.findNodeType(ctx, resolved.get(), version).orElse(null);
            }
            return null;
        });
        return Optional.ofNullable(cached);
    }

    /** SCHEMA-04 historical read via {@code schema_version} snapshot. */
    public Optional<NodeTypeDescriptor> getAt(TenantContext ctx, String typeSlug, long versionNr) {
        return replayer.getAt(ctx, typeSlug, versionNr);
    }

    // ---------- aliases (SCHEMA-05) ----------

    @Transactional(propagation = Propagation.REQUIRED)
    public void renameProperty(TenantContext ctx, String typeSlug, String oldSlug, String newSlug) {
        // In Wave 2 the physical property row is NOT migrated — rename is logical
        // via an alias entry. Writes use newSlug; reads check aliases on miss.
        aliases.recordPropertyAlias(ctx, typeSlug, oldSlug, newSlug);
        // Add the new property row if not yet present, mirroring the old schema.
        // For Wave 2 we only record the alias; the test proves that
        // resolveCurrentPropertySlug(oldSlug) returns newSlug.
        versions.applyChange(
                ctx,
                "RENAME_PROPERTY",
                "{\"changeType\":\"RENAME_PROPERTY\",\"typeSlug\":\"" + typeSlug + "\",\"oldSlug\":\"" + oldSlug
                        + "\",\"newSlug\":\"" + newSlug + "\"}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
    }

    public Optional<String> resolvePropertySlug(TenantContext ctx, String typeSlug, String maybeOldSlug) {
        return aliases.resolveCurrentPropertySlug(ctx, typeSlug, maybeOldSlug);
    }

    // ---------- REST projection queries (W2a) ----------

    /**
     * Return all node types for a given model that have {@code rest_read_enabled = true}.
     * Used by the REST projection dispatcher to determine which types are exposed.
     */
    public List<NodeTypeDescriptor> listExposedTypes(UUID modelId) {
        TenantContext ctx = TenantContext.of(modelId);
        long version = versions.currentVersion(ctx);
        return repo.listExposedNodeTypes(ctx, version);
    }

    /**
     * Return all distinct model IDs that have at least one node type with
     * {@code rest_read_enabled = true}. Used by the OpenAPI customizer to
     * enumerate active models.
     */
    public List<UUID> listDistinctExposedModels() {
        return repo.listDistinctExposedModels();
    }

    // ---------- breaking-change detector (SCHEMA-08) ----------

    /**
     * Attempt to remove a required property. Throws
     * {@link SchemaBreakingChangeException} unless {@code force=true}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void removeRequiredPropertyOrReject(TenantContext ctx, String typeSlug, String propertySlug, boolean force) {
        boolean isRequired = repo.listProperties(ctx, typeSlug).stream()
                .anyMatch(p -> p.slug().equals(propertySlug) && p.required());
        if (isRequired && !force) {
            throw new SchemaBreakingChangeException("Removing required property " + typeSlug + "." + propertySlug
                    + " is a breaking change." + " Pass force=true to override.");
        }
        repo.deleteProperty(ctx, typeSlug, propertySlug);
        versions.applyChange(
                ctx,
                "REMOVE_PROPERTY",
                "{\"changeType\":\"REMOVE_PROPERTY\",\"typeSlug\":\"" + typeSlug + "\",\"propertySlug\":\""
                        + propertySlug + "\",\"force\":" + force + "}",
                "schema-registry");
        cache.invalidateAll(ctx.modelId());
    }
}
