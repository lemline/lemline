// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.info
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.getWorkflowToTest
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import io.kotest.core.spec.style.FunSpec
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The workflow-level `input.from` shapes what the first task receives, on both orchestrators,
 * while `$workflow.input` keeps the input the workflow was started with.
 */
class WorkflowInputTest : FunSpec() {

    private suspend fun runFull(yaml: String, input: JsonNode): WorkflowEvent.Outcome = FullOrchestrator.resume(
        workflow = getWorkflowToTest(yaml),
        command = StepByStepOrchestrator.initCmd(workflowInput = input),
        serde = true,
        activityExecutor = MockActivityExecutor.empty { },
        functionResolver = MockFunctionResolver(),
        cloudEventHook = InMemoryCloudEventHook(),
        lifecycleHook = LifecycleEventHook.NOOP,
    )

    private suspend fun runStepByStep(yaml: String, input: JsonNode): WorkflowEvent {
        val workflow = getWorkflowToTest(yaml)
        return StepByStepOrchestrator.runByActivity(
            workflow = workflow,
            command = StepByStepOrchestrator.initCmd(workflowInput = input),
            workflowInfo = workflow.info,
            lifecycleHook = LifecycleEventHook.NOOP,
        )
    }

    private suspend fun runDocumentationExample(name: String, input: JsonNode): WorkflowEvent {
        val workflow = WorkflowCache.parseYamlAndPut(File("../lemline-docs/snippets/workflows/reference/$name").readText())
        return StepByStepOrchestrator.runByActivity(
            workflow = workflow,
            command = StepByStepOrchestrator.initCmd(workflowInput = input),
            workflowInfo = workflow.info,
            lifecycleHook = LifecycleEventHook.NOOP,
        )
    }

    private val orchestrators: List<Pair<String, suspend (String, JsonNode) -> WorkflowEvent>> = listOf(
        "FullOrchestrator" to ::runFull,
        "StepByStepOrchestrator" to ::runStepByStep,
    )

    private val order = buildJsonObject { put("orderId", "ORD-1") }

    init {
        afterEach {
            WorkflowCache.clear()
        }

        test("the documented default-workflow-input example returns the defaults merged with the input") {
            val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(runDocumentationExample("default-workflow-input.yaml", order))

            assertEquals(
                buildJsonObject {
                    put("orderId", "ORD-1")
                    put("priority", "NORMAL")
                    put("currency", "USD")
                },
                outcome.output,
            )
        }

        test("the documented order-event-schedule example reads the order from the event array") {
            val events = ArrayNode(listOf(buildJsonObject { put("orderId", "ORD-1001") }))
            val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(runDocumentationExample("order-event-schedule.yaml", events))

            assertEquals(
                buildJsonObject {
                    put("orderId", "ORD-1001")
                    put("status", "received")
                },
                outcome.output,
            )
        }

        orchestrators.forEach { (orchestrator, run) ->

            test("$orchestrator applies the workflow-level input.from before the first task") {
                val yaml = $$"""
                    input:
                      from: '${ { priority: "NORMAL", currency: "USD" } + . }'
                    do:
                      - recordOrder:
                          set:
                            orderId: ${ .orderId }
                            priority: ${ .priority }
                            currency: ${ .currency }
                """
                val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(run(yaml, order))

                assertEquals(
                    buildJsonObject {
                        put("orderId", "ORD-1")
                        put("priority", "NORMAL")
                        put("currency", "USD")
                    },
                    outcome.output,
                )
            }

            test("$orchestrator unwraps an event-started array input with the workflow-level input.from") {
                val yaml = $$"""
                    input:
                      from: '${ .[0] }'
                    do:
                      - recordOrder:
                          set:
                            orderId: ${ .orderId }
                            total: ${ .total }
                """
                val events = ArrayNode(listOf(buildJsonObject {
                    put("orderId", "ORD-1001")
                    put("total", 42.5)
                }))
                val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(run(yaml, events))

                assertEquals(
                    buildJsonObject {
                        put("orderId", "ORD-1001")
                        put("total", 42.5)
                    },
                    outcome.output,
                )
            }

            test("$orchestrator keeps the original input in \$workflow.input") {
                val yaml = $$"""
                    input:
                      from: '${ { priority: "NORMAL" } + . }'
                    do:
                      - recordOrder:
                          set:
                            received: ${ . }
                            original: ${ $workflow.input }
                """
                val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(run(yaml, order))

                assertEquals(
                    buildJsonObject {
                        put("received", buildJsonObject {
                            put("priority", "NORMAL")
                            put("orderId", "ORD-1")
                        })
                        put("original", order)
                    },
                    outcome.output,
                )
            }

            test("$orchestrator faults the workflow at its root when the workflow-level input.from fails") {
                val yaml = $$"""
                    input:
                      from: '${ .orderId | error("no order") }'
                    do:
                      - recordOrder:
                          set:
                            orderId: ${ .orderId }
                """
                val outcome = assertIs<WorkflowEvent.WorkflowFaulted>(run(yaml, order))

                assertTrue(outcome.error.type.endsWith(WorkflowErrorType.EXPRESSION.type), outcome.error.type)
                assertEquals(NodePosition.root.toString(), outcome.error.position)
            }

            test("$orchestrator applies the workflow-level output.as after the last task") {
                val yaml = $$"""
                    input:
                      from: '${ { priority: "NORMAL" } + . }'
                    output:
                      as: '${ { summary: (.orderId + ":" + .priority) } }'
                    do:
                      - recordOrder:
                          set:
                            orderId: ${ .orderId }
                            priority: ${ .priority }
                """
                val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(run(yaml, order))

                assertEquals(buildJsonObject { put("summary", "ORD-1:NORMAL") }, outcome.output)
            }
        }
    }
}
