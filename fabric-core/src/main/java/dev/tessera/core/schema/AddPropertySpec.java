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

/** SCHEMA-02 add-property spec. */
public record AddPropertySpec(String slug, String name, String dataType, boolean required) {
    public static AddPropertySpec of(String slug, String dataType) {
        return new AddPropertySpec(slug, slug, dataType, false);
    }

    public static AddPropertySpec required(String slug, String dataType) {
        return new AddPropertySpec(slug, slug, dataType, true);
    }
}
