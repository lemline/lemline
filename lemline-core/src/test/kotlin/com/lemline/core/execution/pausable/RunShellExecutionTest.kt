// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.pausable

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.execution.bases.RunShellExecutionTest
import com.lemline.core.getWorkflowNode
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class PausableRunShellExecutionTest : RunShellExecutionTest() {
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
        return PausableOrchestratorTestHelper.runUntilComplete(rootNode, input)
    }
}
