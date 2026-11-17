// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases.impl

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.common.values.WorkflowId
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.functions.FunctionCache
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlinx.coroutines.flow.onStart

/**
 * WorkflowTestExecutor implementation that uses FullOrchestrator.
 *
 * This executor runs workflows entirely in-memory, handling all async
 * events (waits, retries, child workflows, forks) immediately without
 * involving any external infrastructure.
 *
 * Uses MockActivityExecutor for activity execution - all activities
 * require mock configurations to be provided.
 *
 * For listen tasks, CloudEvents should be provided via the cloudEvents parameter.
 * These events are emitted to an InMemoryCloudEventHook before workflow execution,
 * so listen tasks receive them immediately.
 *
 * Ideal for fast unit tests.
 */
class FullOrchestratorTestExecutor : WorkflowTestExecutor {

    override suspend fun execute(
        yaml: String,
        input: JsonNode,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>,
        mockConfig: MockConfiguration,
        cloudEvents: List<CloudEvent>,
        validateDefinition: Boolean,
        validateLifecycleEvents: ((List<CloudEvent>) -> String?)?,
        rebuildCachesOnListen: Boolean
    ): WorkflowTestResult {
        val events = java.util.concurrent.CopyOnWriteArrayList<CloudEvent>()
        return try {
            // Register dependencies first
            dependencies.forEach { dep ->
                createWorkflow(dep.yaml, dep.namespace, dep.name, dep.version, validateDefinition)
            }
            val workflow = createWorkflow(yaml, namespace, name, version, validateDefinition)

            val startState = StepByStepOrchestrator.initCmd(
                workflowId = WorkflowId.random(),
                workflowInput = input,
                hasWaitingParent = false,
                startedAt = Clock.System.now()
            )

            // Create CloudEventHook and pre-populate with test events
            val cloudEventHook = InMemoryCloudEventHook()
            cloudEvents.forEach { event ->
                cloudEventHook.emit(event)
            }
            val executionCloudEvents = object : CloudEventHook by cloudEventHook {
                override fun receive() = cloudEventHook.receive().onStart {
                    if (rebuildCachesOnListen) {
                        WorkflowCache.clear()
                        FunctionCache.clear()
                        dependencies.forEach { dep ->
                            createWorkflow(dep.yaml, dep.namespace, dep.name, dep.version, validateDefinition)
                        }
                        // FullOrchestrator retains the definition object across its recursive resumes.
                        // Rebuild its node/function caches; the runner reloads the definition from DB.
                        WorkflowCache.putWorkflow(workflow)
                    }
                }
            }

            val lifecycleHook = if (validateLifecycleEvents == null) LifecycleEventHook.NOOP else
                CloudEventLifecycleHook(object : LifecycleEventEmitter {
                    override suspend fun emit(cloudEvent: CloudEvent) { events.add(cloudEvent) }
                })

            // Create FunctionResolver with mock configuration (for remote functions)
            val functionResolver = MockFunctionResolver(mockConfig)

            // MockActivityExecutor handles HTTP, Script, Shell, Emit
            // Functions are handled directly by FullOrchestrator via functionResolver
            val activityExecutor = MockActivityExecutor(mockConfig = mockConfig)

            val event = FullOrchestrator.resume(
                workflow = workflow,
                command = startState,
                serde = true, // For testing, we want to ensure serialization works
                activityExecutor = activityExecutor,
                functionResolver = functionResolver,
                cloudEventHook = executionCloudEvents,
                lifecycleHook = lifecycleHook
            )

            WorkflowTestResult.Success(event.value())
        } catch (e: Exception) {
            val errorDetails = buildString {
                append(e::class.simpleName)
                e.message?.let { append(": $it") }
                // For InternalException, show the error details
                if (e is InternalException) append("\n  Error: ${e.error}")
                // Include cause chain for debugging
                var cause = e.cause
                while (cause != null) {
                    append("\n  Caused by: ${cause::class.simpleName}: ${cause.message}")
                    cause = if (cause.cause != cause) cause.cause else null
                }
            }
            WorkflowTestResult.Failure(
                error = errorDetails,
                exception = e
            )
        }.also { result ->
            validateLifecycleEvents?.invoke(events)?.let { error ->
                throw AssertionError("Lifecycle validation failed: $error; workflow result: $result")
            }
        }
    }

    companion object {
        fun createWorkflow(
            doYaml: String,
            namespace: String = "test",
            name: String = "workflow-${doYaml.hashCode()}",
            version: String = "0.1.0",
            validate: Boolean = true
        ): Workflow {
            val document = """
                document:
                  dsl: '1.0.0'
                  namespace: $namespace
                  name: $name
                  version: $version
            """.trimIndent()
            val workflowYaml = document + "\n" + doYaml.trimIndent()
            return if (validate) {
                WorkflowCache.parseYamlAndPut(workflowYaml)
            } else {
                WorkflowCache.parseYamlAndPutNoValidation(workflowYaml)
            }
        }
    }
}
