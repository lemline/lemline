// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.runner.common.activities.TestModeConfiguration
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlinx.serialization.json.JsonElement

@ApplicationScoped
class ActivityExecutorProducer {

    @Inject
    lateinit var testModeConfiguration: TestModeConfiguration

    private val runnerActivityExecutor by lazy { RunnerActivityExecutor() }

    @Produces
    @ApplicationScoped
    fun produce(): ActivityExecutor = object : ActivityExecutor {
        override suspend fun execute(event: ActivityStarted): JsonElement {
            testModeConfiguration.mockConfiguration?.let {
                return MockActivityExecutor(it).execute(event)
            }

            testModeConfiguration.mockConfigPath?.let {
                return MockActivityExecutor.fromFile(it).execute(event)
            }

            return runnerActivityExecutor.execute(event)
        }
    }
}
