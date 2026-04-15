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

/** SCHEMA-01 create spec — ergonomic constructor for tests. */
public record CreateNodeTypeSpec(String slug, String name, String label, String description) {
    public static CreateNodeTypeSpec of(String slug) {
        return new CreateNodeTypeSpec(slug, slug, slug, null);
    }
}
