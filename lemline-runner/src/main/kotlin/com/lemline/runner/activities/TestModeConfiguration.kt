// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import jakarta.enterprise.context.ApplicationScoped

/**
 * Runtime configuration holder for test mode.
 *
 * This singleton captures the CLI flags from [ListenCommand] and makes them
 * available to CDI beans that need to determine whether test mode is enabled.
 *
 * Test mode is enabled via `--test-mode` flag on the listen command:
 * ```bash
 * ./lemline-runner listen --test-mode --mock-config=/path/to/mocks.yaml
 * ```
 *
 * When test mode is enabled, [TestActivityExecutor] is used instead of
 * [RunnerActivityExecutor] for all activity execution.
 */
@ApplicationScoped
class TestModeConfiguration {

    /**
     * Whether test mode is enabled.
     */
    @Volatile
    private var _isEnabled: Boolean = false
    val isEnabled: Boolean get() = _isEnabled

    /**
     * Path to mock configuration file (YAML/JSON).
     */
    @Volatile
    private var _mockConfigPath: String? = null
    val mockConfigPath: String? get() = _mockConfigPath

    /**
     * Enable test mode with optional mock configuration path.
     *
     * @param mockConfigPath Path to mock configuration file (optional)
     */
    fun enable(mockConfigPath: String? = null) {
        this._isEnabled = true
        this._mockConfigPath = mockConfigPath
    }

    /**
     * Disable test mode (primarily for testing).
     */
    fun disable() {
        this._isEnabled = false
        this._mockConfigPath = null
    }
}
