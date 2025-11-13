// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.continuous

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.bases.TryTaskExecutionTest
import com.lemline.core.orchestrator.executeContinuousWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class ContinuousTryTaskExecutionTest : TryTaskExecutionTest() {
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
