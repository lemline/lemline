// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import com.lemline.runner.common.config.DatabaseConfig

interface OrchestrationTestDatabase : DatabaseConfig, AutoCloseable {
    fun migrate()
}
