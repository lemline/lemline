// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowStep
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@ExperimentalTime
class WorkflowStepBuilderTest : FunSpec({

    test("build workflowStep from simple position") {
        // Given: A simple position /do/taskA with default visitCounts
        val position = NodePosition.parse("/do/taskA")
        val taskStates = mapOf(
            NodePosition.parse("/do") to DoState(startedAt = Clock.System.now(), visitCount = 0, index = 0),
            NodePosition.parse("/do/taskA") to SetState(startedAt = Clock.System.now(), visitCount = 0)
        )

        // When: Building WorkflowStep
        val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

        // Then: Should include visit counts in path
        workflowStep.toString() shouldBe "/do,0/taskA,0"
    }

    test("build workflowStep from nested position") {
        // Given: A nested position /do/taskA/try/failing
        val position = NodePosition.parse("/do/taskA/try/failing")
        val taskStates = mapOf(
            NodePosition.parse("/do") to DoState(startedAt = Clock.System.now(), visitCount = 0, index = 0),
            NodePosition.parse("/do/taskA") to SetState(startedAt = Clock.System.now(), visitCount = 0),
            NodePosition.parse("/do/taskA/try") to TryState(
                startedAt = Clock.System.now(),
                visitCount = 0,
                transformedInput = kotlinx.serialization.json.buildJsonObject {},
                attemptIndex = 0,
                runningCatch = false,
                errorAs = "error"
            ),
            NodePosition.parse("/do/taskA/try/failing") to CallState(
                startedAt = Clock.System.now(),
                visitCount = 0
            )
        )

        // When: Building WorkflowStep
        val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

        // Then: Should build complete path with all visit counts
        workflowStep.toString() shouldBe "/do,0/taskA,0/try,0/failing,0"
    }

    test("build workflowStep with non-zero visit counts") {
        // Given: A position with incremented visitCounts (e.g., after iterations)
        val position = NodePosition.parse("/for/do/processItem")
        val taskStates = mapOf(
            NodePosition.parse("/for") to ForState(
                startedAt = Clock.System.now(),
                visitCount = 2,  // 3rd iteration
                collection = listOf(),
                index = 2,
                forEach = "item",
                forAt = "index"
            ),
            NodePosition.parse("/for/do") to DoState(
                startedAt = Clock.System.now(),
                visitCount = 1,  // 2nd task in do block
                index = 1
            ),
            NodePosition.parse("/for/do/processItem") to SetState(
                startedAt = Clock.System.now(),
                visitCount = 0  // First execution of this task
            )
        )

        // When: Building WorkflowStep
        val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

        // Then: Should reflect actual visit counts
        workflowStep.toString() shouldBe "/for,2/do,1/processItem,0"
    }

    test("build workflowStep for foreach iteration") {
        // Given: Multiple iterations of a for loop
        val position = NodePosition.parse("/for/do/task")
        val iterations = listOf(
            (0 to "/for,0/do,0/task,0"),
            (1 to "/for,1/do,0/task,0"),
            (2 to "/for,2/do,0/task,0")
        )

        iterations.forEach { (iteration, expected) ->
            val taskStates = mapOf(
                NodePosition.parse("/for") to ForState(
                    startedAt = Clock.System.now(),
                    visitCount = iteration,
                    collection = listOf(),
                    index = iteration,
                    forEach = "item",
                    forAt = "index"
                ),
                NodePosition.parse("/for/do") to DoState(
                    startedAt = Clock.System.now(),
                    visitCount = 0,
                    index = 0
                ),
                NodePosition.parse("/for/do/task") to SetState(
                    startedAt = Clock.System.now(),
                    visitCount = 0
                )
            )

            // When: Building WorkflowStep
            val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

            // Then: Each iteration should have unique WorkflowStep
            workflowStep.toString() shouldBe expected
        }
    }

    test("build workflowStep for retry attempts") {
        // Given: Multiple retry attempts in a try block
        val position = NodePosition.parse("/try/failing")
        val attempts = listOf(
            (0 to "/try,0/failing,0"),  // First attempt
            (1 to "/try,1/failing,0"),  // First retry
            (2 to "/try,2/failing,0")   // Second retry
        )

        attempts.forEach { (attemptIndex, expected) ->
            val taskStates = mapOf(
                NodePosition.parse("/try") to TryState(
                    startedAt = Clock.System.now(),
                    visitCount = attemptIndex,  // Increments with each retry
                    transformedInput = kotlinx.serialization.json.buildJsonObject {},
                    attemptIndex = attemptIndex,
                    runningCatch = false,
                    errorAs = "error"
                ),
                NodePosition.parse("/try/failing") to CallState(
                    startedAt = Clock.System.now(),
                    visitCount = 0
                )
            )

            // When: Building WorkflowStep
            val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

            // Then: Each retry should have unique WorkflowStep
            workflowStep.toString() shouldBe expected
        }
    }

    test("build workflowStep handles missing state (defaults to 0)") {
        // Given: A position where some parent states are missing
        val position = NodePosition.parse("/do/taskA/taskB")
        val taskStates = mapOf(
            NodePosition.parse("/do/taskA/taskB") to SetState(
                startedAt = Clock.System.now(),
                visitCount = 0
            )
            // Missing states for /do and /do/taskA - should default to visitCount=0
        )

        // When: Building WorkflowStep
        val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)

        // Then: Should use visitCount=0 for missing states
        workflowStep.toString() shouldBe "/do,0/taskA,0/taskB,0"
    }

    test("workflowStep can convert back to NodePosition") {
        // Given: A WorkflowStep with visit counts
        val position = NodePosition.parse("/for/do/task")
        val taskStates = mapOf(
            NodePosition.parse("/for") to ForState(
                startedAt = Clock.System.now(),
                visitCount = 2,
                collection = listOf(),
                index = 2,
                forEach = "item",
                forAt = "index"
            ),
            NodePosition.parse("/for/do") to DoState(
                startedAt = Clock.System.now(),
                visitCount = 1,
                index = 1
            ),
            NodePosition.parse("/for/do/task") to SetState(
                startedAt = Clock.System.now(),
                visitCount = 0
            )
        )

        // When: Building WorkflowStep and converting back
        val workflowStep = WorkflowStepBuilder.buildWorkflowStep(position, taskStates)
        val recoveredPosition = workflowStep.toNodePosition()

        // Then: Should recover original static position
        recoveredPosition shouldBe position
    }
})
