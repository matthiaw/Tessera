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
package dev.tessera.projections.mcp.interceptor;

/**
 * Wraps every MCP tool response in {@code <data>...</data>} markers to mitigate
 * prompt injection (SEC-08, D-D1). Applied ONLY by
 * {@link dev.tessera.projections.mcp.adapter.SpringAiMcpAdapter} — individual
 * tools must NOT call this directly.
 */
public final class ToolResponseWrapper {

    private static final String OPEN = "<data>";
    private static final String CLOSE = "</data>";

    private ToolResponseWrapper() {}

    /**
     * Wraps {@code rawContent} in {@code <data>...</data>} markers.
     * A {@code null} input yields {@code <data></data>}.
     */
    public static String wrap(String rawContent) {
        if (rawContent == null) {
            return OPEN + CLOSE;
        }
        return OPEN + rawContent + CLOSE;
    }
}
