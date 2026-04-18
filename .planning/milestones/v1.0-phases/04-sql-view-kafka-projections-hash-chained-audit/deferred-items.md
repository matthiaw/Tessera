# Deferred Items — Phase 04

## Pre-existing Compile Breaks in fabric-app

Discovered during Phase 04 Plan 00 (Wave 0 stubs). These are out of scope for the stub
creation plan and must be fixed before or as part of the plan that touches
`MarkdownFolderConnectorIT.java`.

### DEF-01: MarkdownFolderConnectorIT anonymous GraphRepository missing findShortestPath

- **File:** `fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java`
- **Line:** 367
- **Issue:** The anonymous `GraphRepository` implementation does not override
  `findShortestPath(TenantContext, UUID, UUID)`, which was added to the interface
  in Phase 03. Anonymous class does not compile without implementing all abstract methods.
- **Fix:** Add `findShortestPath` override returning `List.of()` (or throw
  `UnsupportedOperationException`) in the test's anonymous `GraphRepository`.
- **Cause:** Phase 03 added `findShortestPath` to `GraphRepository` interface but the
  existing `MarkdownFolderConnectorIT` was not updated.

### DEF-02: MarkdownFolderConnectorIT references ResolutionResult.ReviewQueue which no longer exists

- **File:** `fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java`
- **Line:** 451
- **Issue:** References `ResolutionResult.ReviewQueue` which was renamed to
  `ResolutionResult.NeedsReview` in the production code.
- **Fix:** Replace `ResolutionResult.ReviewQueue` with `ResolutionResult.NeedsReview`
  in the mock setup.
- **Cause:** Rename during Phase 02.5 or 03 was not propagated to all test usages.
