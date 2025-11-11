// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.execution.bases.WaitExecutionTest
import com.lemline.core.getWorkflowNode
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class CompleteWaitExecutionTest : WaitExecutionTest() {
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
    ): JsonElement {
        val rootNode = getWorkflowNode(yaml, namespace, name, version)
        return CompleteOrchestrator.run(rootNode, input)
    }
}
