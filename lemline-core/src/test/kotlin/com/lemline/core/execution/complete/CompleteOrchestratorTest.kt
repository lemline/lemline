// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.execution.bases.AbstractOrchestratorTest
import com.lemline.core.getWorkflowNode
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Tests for CompleteOrchestrator using shared test scenarios.
 *
 * CompleteOrchestrator executes workflows from start to finish:
 * - Activities execute fully (real HTTP calls, shell commands, scripts)
 * - Delays actually wait using coroutines
 * - Sub-workflows execute inline recursively
 * - Returns final workflow output
 *
 * This test class extends AbstractOrchestratorTest which contains all common
 * workflow execution scenarios. The only difference from PausableOrchestrator
 * is the execution model - CompleteOrchestrator runs workflows to completion
 * in one call.
 */
@ExperimentalTime
class CompleteOrchestratorTest : AbstractOrchestratorTest() {

    /**
     * Execute workflow to completion using CompleteOrchestrator.
     *
     * This implementation simply calls CompleteOrchestrator.run() and returns
     * the final output. No pause handling needed.
     */
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
