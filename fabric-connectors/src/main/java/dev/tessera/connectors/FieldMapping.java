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

/**
 * Maps one source field to one Tessera property via JSONPath + optional transform.
 *
 * @param target     Tessera property name
 * @param sourcePath JSONPath expression (e.g. "$.attributes.email")
 * @param transform  transform name from {@link TransformRegistry}, or null for passthrough
 * @param required   if true, a missing source value sends the row to DLQ
 */
public record FieldMapping(String target, String sourcePath, String transform, boolean required) {}
