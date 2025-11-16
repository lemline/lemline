// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.continuous

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.bases.CallHttpExecutionTest
import com.lemline.core.orchestrator.executeContinuousWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class ContinuousCallHttpExecutionTest : CallHttpExecutionTest() {
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
    ): JsonElement = executeContinuousWorkflow(yaml, namespace, name, version, input)
}
