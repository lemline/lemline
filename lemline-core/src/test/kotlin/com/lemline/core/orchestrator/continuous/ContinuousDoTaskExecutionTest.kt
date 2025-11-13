// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.continuous

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.bases.DoTaskExecutionTest
import com.lemline.core.orchestrator.executeContinuousWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class ContinuousDoTaskExecutionTest : DoTaskExecutionTest() {
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
