// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states.protobuf

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.InternalException
import com.lemline.core.random.random
import com.lemline.core.states.CallFunctionState
import com.lemline.core.states.DoState
import com.lemline.core.states.ForState
import com.lemline.core.states.ForeachState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.NodeState
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryState
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class NodeStackProtobufMapperTest {

    @Test
    fun `should round-trip random node stacks`() {
        repeat(50) {
            val original = NodeStack.random()
            val encoded = NodeStackProtobufMapper.toProto(original)
            val decoded = NodeStackProtobufMapper.fromProto(encoded)
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `should round-trip all node state variants`() {
        val sampleStates = listOf<NodeState>(
            RootState(
                startedAt = Instant.fromEpochSeconds(1735732800),
                workflowId = WorkflowId.random(),
                workflowInput = JsonPrimitive("input"),
                hasWaitingParent = true
            ),
            DoState(
                startedAt = Instant.fromEpochSeconds(1735732801),
                index = 42
            ),
            ForState(
                startedAt = Instant.fromEpochSeconds(1735732802),
                collection = listOf(JsonPrimitive(1), JsonPrimitive("value")),
                index = 2,
                forEach = "item",
                forAt = "index"
            ),
            ForeachState(
                startedAt = Instant.fromEpochSeconds(1735732803),
                item = JsonPrimitive("current"),
                index = 3,
                itemVar = "it",
                indexVar = "idx"
            ),
            TaskState(
                startedAt = Instant.fromEpochSeconds(1735732804)
            ),
            CallFunctionState(
                startedAt = Instant.fromEpochSeconds(1735732805),
                entered = true
            ),
            TryState(
                startedAt = Instant.fromEpochSeconds(1735732806),
                transformedInput = JsonPrimitive("transformed"),
                attemptIndex = 3,
                runningCatch = true,
                lastError = InternalException.Error(
                    type = "TestError",
                    status = 500,
                    position = "/do/0/task",
                    title = "boom",
                    details = "stack"
                ),
                errorAs = "error",
                hasStarted = true
            ),
        )

        sampleStates.forEach { state ->
            assertEquals(state, roundTrip(state))
        }
    }

    @Test
    fun `should preserve null try-state error`() {
        val state = TryState(
            startedAt = Instant.fromEpochSeconds(1735732807),
            transformedInput = JsonPrimitive("input"),
            attemptIndex = 0,
            runningCatch = false,
            lastError = null,
            errorAs = "error",
            hasStarted = false
        )

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `should preserve null foreach item`() {
        val state = ForeachState(
            startedAt = Instant.fromEpochSeconds(1735732808),
            item = JsonNull,
            index = 1,
            itemVar = "item",
            indexVar = "index"
        )

        assertEquals(state, roundTrip(state))
    }

    private fun roundTrip(state: NodeState): NodeState {
        val rootState = RootState(
            startedAt = Instant.fromEpochSeconds(1735732700),
            workflowId = WorkflowId.random(),
            workflowInput = JsonPrimitive("root-input")
        )

        val stack = when (state) {
            is RootState -> NodeStack(
                listOf(
                    StackFrame(NodePosition.root, state, 1),
                )
            )

            else -> NodeStack(
                listOf(
                    StackFrame(NodePosition.root, rootState, 1),
                    StackFrame(NodePosition("/do/0/state"), state, 2),
                )
            )
        }

        val encoded = NodeStackProtobufMapper.toProto(stack)
        val decoded = NodeStackProtobufMapper.fromProto(encoded)

        return decoded.currentState
    }
}
