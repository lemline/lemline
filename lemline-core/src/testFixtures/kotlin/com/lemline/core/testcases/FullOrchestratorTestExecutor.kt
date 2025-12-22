// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.common.values.WorkflowId
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.workflows.DefinitionCache
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

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

    @ExperimentalTime
    override suspend fun execute(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>,
        mockConfig: MockConfiguration,
        cloudEvents: List<CloudEvent>
    ): WorkflowTestResult {
        return try {
            // Register dependencies first
            dependencies.forEach { dep ->
                createWorkflow(dep.yaml, dep.namespace, dep.name, dep.version)
            }
            val workflow = createWorkflow(yaml, namespace, name, version)

            val startState = StepByStepOrchestrator.initCmd(
                workflowId = WorkflowId.random(),
                workflowInput = input,
                hasWaitingParent = false,
                startedAt = Clock.System.now()
            )

            // Use MockActivityExecutor with the provided configuration
            val activityExecutor = MockActivityExecutor(mockConfig)

            // Create CloudEventHook and pre-populate with test events
            val cloudEventHook = InMemoryCloudEventHook()
            cloudEvents.forEach { event ->
                cloudEventHook.emit(event)
            }

            val event = FullOrchestrator.resume(
                workflow = workflow,
                command = startState,
                serde = true, // For testing, we want to ensure serialization works
                activityExecutor = activityExecutor,
                cloudEventHook = cloudEventHook,
                lifecycleHook = LifecycleEventHook.NOOP
            )

            WorkflowTestResult.Success(event.value())
        } catch (e: Exception) {
            WorkflowTestResult.Failure(
                error = e.message ?: e::class.simpleName ?: "Unknown error",
                exception = e
            )
        }
    }

    companion object {
        /**
         * Creates a Workflow from a YAML do section.
         *
         * @param doYaml The workflow do section in YAML format
         * @param namespace Workflow namespace
         * @param name Workflow name
         * @param version Workflow version
         * @return Parsed Workflow
         */
        fun createWorkflow(
            doYaml: String,
            namespace: String = "test",
            name: String = "workflow-${doYaml.hashCode()}",
            version: String = "0.1.0"
        ): Workflow {
            val document = """
                document:
                  dsl: '1.0.0'
                  namespace: $namespace
                  name: $name
                  version: $version
            """.trimIndent()
            val workflowYaml = document + "\n" + doYaml.trimIndent()
            return DefinitionCache.parseAndPut(workflowYaml)
        }
    }
}
