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
package dev.tessera.projections.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * SEC-07: AgentQuotaService must enforce per-agent write quotas.
 *
 * <p>Default quota = 0 writes (read-only by default). Stub created in Wave 0; fleshed out after
 * Plan 03 creates AgentQuotaService.
 */
class AgentQuotaServiceTest {

    @Test
    @Disabled("Stub: enable after Plan 03 creates AgentQuotaService")
    void rejects_write_when_no_quota_row_exists() {
        // checkWriteQuota() with no mcp_agent_quotas row -> QuotaExceededException
        assertThatThrownBy(() -> {
                    throw new UnsupportedOperationException("stub");
                })
                .isInstanceOf(UnsupportedOperationException.class); // replace with real assertion
    }

    @Test
    @Disabled("Stub: enable after Plan 03 creates AgentQuotaService")
    void rejects_write_when_quota_is_zero() {
        // Insert quota row with writes_per_hour=0, checkWriteQuota() -> QuotaExceededException
        assertThatThrownBy(() -> {
                    throw new UnsupportedOperationException("stub");
                })
                .isInstanceOf(UnsupportedOperationException.class); // replace with real assertion
    }

    @Test
    @Disabled("Stub: enable after Plan 03 creates AgentQuotaService")
    void allows_writes_within_quota_then_rejects() {
        // Insert quota row with writes_per_hour=2
        // checkWriteQuota() x2 -> success
        // checkWriteQuota() x1 -> QuotaExceededException
        assertThatThrownBy(() -> {
                    throw new UnsupportedOperationException("stub");
                })
                .isInstanceOf(UnsupportedOperationException.class); // replace with real assertion
    }
}
