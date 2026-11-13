// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.common.values.info
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.testcases.impl.FullOrchestratorTestExecutor
import com.lemline.core.testcases.impl.WorkflowTestResult
import io.cloudevents.CloudEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RaiseExecutionTest : FunSpec({
    test("named error raises its declared fields and evaluates expressions") {
        val result = FullOrchestratorTestExecutor().execute(
            yaml = $$"""
                use:
                  errors:
                    notImplemented:
                      type: https://serverlessworkflow.io/errors/not-implemented
                      status: 501
                      title: ${ "Not implemented for \(.reason)" }
                      detail: ${ "Workflow '\($workflow.definition.document.name):\($workflow.definition.document.version)' cannot run \(.reason)" }
                do:
                  - notImplemented:
                      raise:
                        error: notImplemented
            """.trimIndent(),
            input = buildJsonObject { put("reason", "yet") },
            name = "raise-reusable"
        )

        val error = (result as WorkflowTestResult.Failure).exception as InternalException
        error.error.type shouldBe "https://serverlessworkflow.io/errors/not-implemented"
        error.error.status shouldBe 501
        error.error.title shouldBe "Not implemented for yet"
        error.error.details shouldBe "Workflow 'raise-reusable:0.1.0' cannot run yet"
    }

    test("inline error also evaluates its detail") {
        val result = FullOrchestratorTestExecutor().execute(
            yaml = $$"""
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/errors/inline
                          status: 400
                          detail: '${ "Bad value: \(.value)" }'
            """.trimIndent(),
            input = buildJsonObject { put("value", "x") }
        )

        val error = (result as WorkflowTestResult.Failure).exception as InternalException
        error.error.type shouldBe "https://serverlessworkflow.io/errors/inline"
        error.error.details shouldBe "Bad value: x"
    }

    test("fault lifecycle event preserves error instance and detail") {
        val workflow = FullOrchestratorTestExecutor.createWorkflow(
            """
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/errors/inline
                          status: 400
            """.trimIndent()
        )
        val nodeStack = StepByStepOrchestrator.initCmd().nodeStack
        val error = InternalException.Error(
            type = "https://serverlessworkflow.io/errors/inline",
            status = 400,
            position = "/do/0/raiseError",
            title = "Bad input",
            details = "Bad value: x"
        )
        lateinit var emitted: CloudEvent
        val hook = CloudEventLifecycleHook(object : LifecycleEventEmitter {
            override suspend fun emit(cloudEvent: CloudEvent) {
                emitted = cloudEvent
            }
        })

        hook.onWorkflowFaulted(workflow.info, nodeStack, error, Clock.System.now())

        val payload = Json.parseToJsonElement(emitted.data!!.toBytes().toString(Charsets.UTF_8))
        val eventError = payload.jsonObject.getValue("error").jsonObject
        eventError.getValue("type").jsonPrimitive.content shouldBe error.type
        eventError.getValue("status").jsonPrimitive.content shouldBe "400"
        eventError.getValue("instance").jsonPrimitive.content shouldBe error.position
        eventError.getValue("detail").jsonPrimitive.content shouldBe error.details
    }
})
