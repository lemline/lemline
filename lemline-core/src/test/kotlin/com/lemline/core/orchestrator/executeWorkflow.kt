// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator

import com.lemline.common.values.WorkflowId
import com.lemline.core.getWorkflowToTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalTime::class)
internal suspend fun executeWorkflow(
    yaml: String,
    input: JsonElement = buildJsonObject { },
    namespace: String = "default",
    name: String = "test",
    version: String = "0.1.0",
): JsonElement {
    val workflow = getWorkflowToTest(yaml, namespace, name, version)

    val startState = StepOrchestrator.initCmd(
        workflowId = WorkflowId.random(),
        workflowInput = input,
        hasWaitingParent = false,
        startedAt = Clock.System.now()
    )

    val event = WorkflowOrchestrator.resume(
        workflow = workflow,
        command = startState,
    )

    return event.value()
}
