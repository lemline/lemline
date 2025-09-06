// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.instances

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowState
import com.lemline.core.workflows.WorkflowVersion
import com.lemline.runner.models.IDV7
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@ExperimentalTime
internal class InstanceMessageTest {

    companion object {
        fun sampleInstance(): InstanceMessage {
            return InstanceMessage(
                workflowState = WorkflowState(
                    workflowId = WorkflowId.new(),
                    workflowName = WorkflowName("test-workflow"),
                    workflowVersion = WorkflowVersion(Random.nextBytes(10).toString()),
                    currentPosition = NodePosition.root,
                    currentStates = NodeStates(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
                ),
                parentId = IDV7.new()
            )
        }
    }


    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val instanceMessage = sampleInstance()
        val workflowId = instanceMessage.workflowId
        val parentId = instanceMessage.parentId
        val workflowName = instanceMessage.workflowName
        val workflowVersion = instanceMessage.workflowVersion

        // When
        Assertions.assertEquals(
            """{"i":"$workflowId","n":"$workflowName","v":"$workflowVersion","p":"","s":{"":{"inp":""}},"w":"$parentId"}""",
            instanceMessage.toJsonString(),
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val original = sampleInstance()

        // When
        val json = LemlineJson.encodeToString(original)
        val deserialized = InstanceMessage.fromJsonString(json)

        // When
        Assertions.assertEquals(original, deserialized)
    }
}
