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
package dev.tessera.connectors;

import java.util.Map;

/**
 * A row that failed mapping inside the connector (before reaching
 * {@code GraphService.apply}). Written to the DLQ by the runner.
 *
 * @param reason     machine-readable reason (e.g. "MAPPING_ERROR")
 * @param detail     human-readable detail
 * @param rawPayload the original source row data
 */
public record DlqEntry(String reason, String detail, Map<String, Object> rawPayload) {}
