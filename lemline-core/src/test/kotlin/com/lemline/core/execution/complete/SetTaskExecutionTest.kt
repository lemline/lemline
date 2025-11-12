// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.execution.bases.SetTaskExecutionTest
import com.lemline.core.execution.executeContinuousWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * SetTask execution tests using CompleteOrchestrator.
 *
 * Runs the base SetTask test suite with CompleteOrchestrator, which executes
 * workflows to completion without pausing.
 */
@ExperimentalTime
internal class CompleteSetTaskExecutionTest : SetTaskExecutionTest() {

    init {
        afterEach {
            DefinitionCache.clear()
        }
    }

    override suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String
    ): JsonElement = executeContinuousWorkflow(yaml, input, namespace, name, version)
}
