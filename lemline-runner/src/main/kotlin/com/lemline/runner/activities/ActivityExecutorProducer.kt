// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.logger.logger
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.runner.common.activities.TestModeConfiguration
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * CDI producer that selects the appropriate [ActivityExecutor] implementation
 * based on the test mode configuration.
 *
 * When `--mock-config` is provided via CLI, [MockActivityExecutor] is used.
 * Otherwise, [RunnerActivityExecutor] is used for normal operation.
 */
@ApplicationScoped
class ActivityExecutorProducer {

    private val logger = logger()

    @Inject
    lateinit var testModeConfiguration: TestModeConfiguration

    // Lazy-initialized RunnerActivityExecutor (not a CDI bean)
    @OptIn(ExperimentalTime::class)
    private val runnerActivityExecutor: RunnerActivityExecutor by lazy {
        RunnerActivityExecutor()
    }

    /**
     * Produces the appropriate ActivityExecutor based on test mode configuration.
     */
    @OptIn(ExperimentalTime::class)
    @Produces
    @ApplicationScoped
    fun produceActivityExecutor(): ActivityExecutor {
        return if (testModeConfiguration.isEnabled) {
            logger.info { "Test mode enabled - using MockActivityExecutor" }
            val mockConfigPath = testModeConfiguration.mockConfigPath
            if (mockConfigPath != null) {
                MockActivityExecutor.fromFile(mockConfigPath)
            } else {
                MockActivityExecutor.empty()
            }
        } else {
            logger.debug { "Normal mode - using RunnerActivityExecutor" }
            runnerActivityExecutor
        }
    }
}
