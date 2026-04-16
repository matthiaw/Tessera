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
package dev.tessera.projections.rest;

/**
 * Decision 11: thrown when a JWT's {@code tenant} claim does not match the
 * {@code {model}} path segment. Always maps to 404 (never 403) to avoid
 * leaking tenant existence.
 */
public class CrossTenantException extends RuntimeException {

    public CrossTenantException() {
        super("Cross-tenant access denied");
    }
}
