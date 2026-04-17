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
package dev.tessera.core.events.snapshot;

import java.time.Instant;

/**
 * OPS-03: value object returned by {@link EventSnapshotService#compact(java.util.UUID)}.
 *
 * @param boundary the compaction timestamp — events before this point have been replaced by SNAPSHOT rows
 * @param eventsWritten number of SNAPSHOT events inserted during Phase 2 of compaction
 * @param eventsDeleted number of pre-boundary non-SNAPSHOT events deleted during Phase 3
 */
public record SnapshotResult(Instant boundary, int eventsWritten, int eventsDeleted) {}
