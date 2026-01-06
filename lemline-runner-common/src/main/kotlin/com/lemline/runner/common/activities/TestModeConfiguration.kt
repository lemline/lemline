// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.activities

import com.lemline.core.activities.mock.MockConfiguration
import jakarta.enterprise.context.ApplicationScoped

/**
 * Runtime holder for mock configuration used by ActivityExecutor.
 *
 * Supports two sources:
 * - File path: Set via CLI `--mock-config=/path/to/mocks.yaml`
 * - Programmatic: Set via [setMockConfiguration] for per-test mocking
 *
 * Programmatic config takes precedence over file path.
 */
@ApplicationScoped
class TestModeConfiguration {

    @Volatile
    var mockConfigPath: String? = null
        private set

    @Volatile
    var mockConfiguration: MockConfiguration? = null
        private set

    fun enable(mockConfigPath: String? = null) {
        this.mockConfigPath = mockConfigPath
    }

    fun setMockConfiguration(config: MockConfiguration?) {
        this.mockConfiguration = config
    }

    fun clearMockConfiguration() {
        this.mockConfiguration = null
    }
}
