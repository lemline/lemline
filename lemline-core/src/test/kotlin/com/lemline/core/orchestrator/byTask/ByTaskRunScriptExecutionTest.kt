// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.byTask

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.bases.RunScriptExecutionTest
import com.lemline.core.orchestrator.executeTaskByTaskWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class ByTaskRunScriptExecutionTest : RunScriptExecutionTest() {
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
    ): JsonElement = executeTaskByTaskWorkflow(yaml, input, namespace, name, version)
}
