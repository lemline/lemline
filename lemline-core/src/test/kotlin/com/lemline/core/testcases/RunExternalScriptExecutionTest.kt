// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.WorkflowId
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.testcases.impl.FullOrchestratorTestExecutor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Tests for `run.script` tasks that load their code from `source.endpoint`.
 */
class RunExternalScriptExecutionTest : FunSpec({

    val scriptContent = "print('hello from an external script')\n"
    val scriptFile = Files.createTempFile("lemline-external-script", ".py")

    beforeSpec { Files.writeString(scriptFile, scriptContent) }
    afterSpec { Files.deleteIfExists(scriptFile) }

    /** Runs the workflow and returns its output with the script configurations handed to the executor. */
    suspend fun run(doYaml: String, input: JsonNode = buildJsonObject { }): Pair<JsonNode, List<RunScriptConfig>> {
        val configs = mutableListOf<RunScriptConfig>()
        val executor = object : ActivityExecutor {
            override suspend fun execute(event: ActivityStarted): JsonNode {
                configs.add((event as RunScriptStarted).config)
                return jsonValue("script executed")
            }
        }
        val event = FullOrchestrator.resume(
            workflow = FullOrchestratorTestExecutor.createWorkflow(doYaml),
            command = StepByStepOrchestrator.initCmd(
                workflowId = WorkflowId.random(),
                workflowInput = input,
                hasWaitingParent = false,
                startedAt = Clock.System.now()
            ),
            serde = true,
            activityExecutor = executor,
            functionResolver = MockFunctionResolver(MockConfiguration()),
            cloudEventHook = InMemoryCloudEventHook(),
            lifecycleHook = LifecycleEventHook.NOOP
        )
        return event.value() to configs
    }

    test("loads the script from a plain string endpoint") {
        val (output, configs) = run(
            """
                do:
                  - runExternal:
                      run:
                        script:
                          language: python
                          source:
                            endpoint: ${scriptFile.toUri()}
            """.trimIndent()
        )

        output shouldBe jsonValue("script executed")
        configs.single().code shouldBe scriptContent
        configs.single().language shouldBe "python"
    }

    test("loads the script from an endpoint object") {
        val (_, configs) = run(
            """
                do:
                  - runExternal:
                      run:
                        script:
                          language: python
                          source:
                            endpoint:
                              uri: ${scriptFile.toUri()}
            """.trimIndent()
        )

        configs.single().code shouldBe scriptContent
    }

    test("loads the script from an endpoint given as a runtime expression") {
        val (_, configs) = run(
            $$"""
                do:
                  - runExternal:
                      run:
                        script:
                          language: python
                          source:
                            endpoint: ${ .scriptUri }
            """.trimIndent(),
            input = buildJsonObject { put("scriptUri", scriptFile.toUri().toString()) }
        )

        configs.single().code shouldBe scriptContent
    }

    test("faults with a configuration error when the script file does not exist") {
        val missing = scriptFile.resolveSibling("lemline-missing-script.py").toUri()
        val configs = mutableListOf<RunScriptConfig>()

        val error = shouldThrow<InternalException> {
            run(
                """
                    do:
                      - runExternal:
                          run:
                            script:
                              language: python
                              source:
                                endpoint: $missing
                """.trimIndent()
            ).also { configs.addAll(it.second) }
        }

        error.error.type shouldContain "configuration"
        error.error.title.orEmpty() + error.error.detail.orEmpty() shouldContain "Script file not found"
        configs.shouldBeEmpty()
    }

    test("faults with a configuration error for a scheme other than file") {
        val error = shouldThrow<InternalException> {
            run(
                """
                    do:
                      - runExternal:
                          run:
                            script:
                              language: python
                              source:
                                endpoint: https://example.com/scripts/validator.py
                """.trimIndent()
            )
        }

        error.error.type shouldContain "configuration"
        error.error.title.orEmpty() + error.error.detail.orEmpty() shouldContain "Unsupported scheme"
    }
})
