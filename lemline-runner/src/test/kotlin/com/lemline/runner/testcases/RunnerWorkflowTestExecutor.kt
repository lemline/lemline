// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.testcases.WorkflowDependency
import com.lemline.core.testcases.WorkflowTestExecutor
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.commands.WorkflowCommandHandler
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.messaging.events.WorkflowEventHandler
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySink
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Workflow test executor that uses the runner's messaging infrastructure with callback-driven routing.
 *
 * This executor leverages the automatic message consumption by [WorkflowCommandHandler] and
 * [WorkflowEventHandler] in QuarkusTest. It uses [onCompleteTest] and [onFailureTest] callbacks
 * to track message processing and route outputs between channels.
 *
 * Flow:
 * 1. Register workflow definitions in the database
 * 2. Set up callbacks on both handlers to track processing
 * 3. Send initial command to commands-in channel
 * 4. Handlers automatically consume and process messages
 * 5. After each message completes, route outputs from sinks to appropriate sources
 * 6. Detect workflow completion/failure from WorkflowCompleted/WorkflowFailed events
 *
 * For wait/retry events in tests, the executor immediately resumes execution
 * (bypassing database persistence) to allow fast test execution.
 */
@Singleton
@ExperimentalTime
@ExperimentalSerializationApi
internal class RunnerWorkflowTestExecutor : WorkflowTestExecutor {

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var commandHandler: WorkflowCommandHandler

    @Inject
    lateinit var eventHandler: WorkflowEventHandler

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    private lateinit var commandsSource: InMemorySource<String>
    private lateinit var commandsSink: InMemorySink<String>
    private lateinit var eventsSource: InMemorySource<String>
    private lateinit var eventsSink: InMemorySink<String>

    private fun initializeChannels() {
        commandsSource = connector.source(COMMANDS_IN_CHANNEL)
        commandsSink = connector.sink(COMMANDS_OUT_CHANNEL)
        eventsSource = connector.source(EVENTS_IN_CHANNEL)
        eventsSink = connector.sink(EVENTS_OUT_CHANNEL)
    }

    private fun clearChannels() {
        if (::commandsSink.isInitialized) commandsSink.clear()
        if (::eventsSink.isInitialized) eventsSink.clear()
    }

    override suspend fun execute(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>
    ): WorkflowTestResult {
        // Generate unique workflow name based on yaml content to avoid cache conflicts
        val uniqueName = "workflow-${yaml.hashCode()}"
        val mainWorkflowId = WorkflowId.random()

        // Use atomic variables for thread-safe result tracking
        var result: WorkflowTestResult? = null
        val hasError = AtomicBoolean(false)

        return try {
            // Initialize in-memory channels
            initializeChannels()
            clearChannels()

            // Setup callbacks on command handler (just for error tracking)
            commandHandler.onCompleteTest = { _: Message<String>, _: InstanceMessage<*>? -> }
            commandHandler.onFailureTest = { _: Message<String>, error: Throwable? ->
                if (error != null && !hasError.getAndSet(true)) {
                    val exception = error as? Exception ?: RuntimeException(error)
                    result = WorkflowTestResult.Failure(
                        error = error.message ?: error::class.simpleName ?: "Unknown error",
                        exception = exception
                    )
                }
            }

            // Setup callbacks on event handler (just for error tracking)
            eventHandler.onCompleteTest = { _: Message<String>, _: InstanceMessage<WorkflowEvent>? -> }
            eventHandler.onFailureTest = { _: Message<String>, error: Throwable? ->
                if (error != null && !hasError.getAndSet(true)) {
                    val exception = error as? Exception ?: RuntimeException(error)
                    result = WorkflowTestResult.Failure(
                        error = error.message ?: error::class.simpleName ?: "Unknown error",
                        exception = exception
                    )
                }
            }

            // Register dependencies in database with their actual names
            // (the parent workflow references them by name, not hash)
            dependencies.forEach { dep ->
                registerWorkflowInDatabase(dep.yaml, dep.namespace, dep.name, dep.version)
            }

            // Register main workflow in database
            registerWorkflowInDatabase(yaml, namespace, uniqueName, version)

            // Create initial command
            val workflowInfo = WorkflowInfo(
                WorkflowNamespace(namespace),
                WorkflowName(uniqueName),
                WorkflowVersion(version)
            )

            // Set hasWaitingParent = true so that WorkflowCompleted events are always
            // emitted to the events channel (otherwise they're only emitted for parent/scheduled workflows)
            val initialCommand = StepByStepOrchestrator.initCmd(
                workflowId = mainWorkflowId,
                workflowInput = input,
                hasWaitingParent = true,
                startedAt = Clock.System.now()
            )
            val initialMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = initialCommand
            )

            // Send initial command to commands channel
            commandsSource.send(initialMessage.toJsonString())

            // Process messages until workflow completes
            processUntilCompletion(mainWorkflowId, { result }, { result = it }, timeoutSeconds = 30)
        } catch (e: Exception) {
            WorkflowTestResult.Failure(
                error = e.message ?: e::class.simpleName ?: "Unknown error",
                exception = e
            )
        } finally {
            // Reset callbacks to no-op
            commandHandler.onCompleteTest = { _, _ -> }
            commandHandler.onFailureTest = { _, _ -> }
            eventHandler.onCompleteTest = { _, _ -> }
            eventHandler.onFailureTest = { _, _ -> }
            clearChannels()
        }
    }

    /**
     * Routes messages between channels until the workflow completes or times out.
     *
     * Uses coroutine delay for non-blocking waiting between routing iterations.
     * Routes:
     * - Commands from commands-out back to commands-in
     * - Events from events-out to events-in (or handles them directly for wait/retry/completion)
     */
    private suspend fun processUntilCompletion(
        mainWorkflowId: WorkflowId,
        getResult: () -> WorkflowTestResult?,
        setResult: (WorkflowTestResult) -> Unit,
        timeoutSeconds: Long = 30
    ): WorkflowTestResult {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000
        var iterations = 0
        val maxIterations = 10000

        while (getResult() == null && iterations < maxIterations) {
            iterations++

            // Check timeout
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                return WorkflowTestResult.Failure(
                    error = "Workflow did not complete within $timeoutSeconds seconds",
                    exception = TimeoutException("Workflow execution timeout")
                )
            }

            // Allow message handlers to process (longer delay for first iteration)
            delay(if (iterations == 1) 100 else 20)

            // Route messages from sinks to sources
            routeMessages(mainWorkflowId, getResult, setResult)

            // If no activity and no result, check for stalled workflow
            // Use longer delays to account for async handler processing (e.g., script execution)
            if (getResult() == null) {
                val eventsEmpty = eventsSink.received().isEmpty()
                val commandsEmpty = commandsSink.received().isEmpty()
                if (eventsEmpty && commandsEmpty) {
                    // Wait longer for handlers to complete (scripts can take 500ms+)
                    delay(200)
                    if (eventsSink.received().isEmpty() && commandsSink.received().isEmpty()) {
                        // Check a few more times with longer delays
                        delay(300)
                        if (eventsSink.received().isEmpty() && commandsSink.received().isEmpty()) {
                            delay(500)
                            if (eventsSink.received().isEmpty() && commandsSink.received().isEmpty()) {
                                break
                            }
                        }
                    }
                }
            }
        }

        return getResult() ?: WorkflowTestResult.Failure(
            error = "Workflow did not complete within $maxIterations iterations",
            exception = null
        )
    }

    /**
     * Routes messages from output sinks to input sources.
     *
     * Handles special cases:
     * - WorkflowCompleted/WorkflowFailed for main workflow -> sets the result
     * - WaitStarted/TaskRetryScheduled -> immediately resumes (bypasses DB for fast tests)
     * - Other events -> routes to events-in for database handler
     * - Commands -> routes back to commands-in
     */
    private fun routeMessages(
        mainWorkflowId: WorkflowId,
        getResult: () -> WorkflowTestResult?,
        setResult: (WorkflowTestResult) -> Unit
    ) {
        // Route events from events-out
        val events = eventsSink.received().toList()
        if (events.isNotEmpty()) {
            eventsSink.clear()

            for (eventMsg in events) {
                val event = InstanceMessage.fromJsonString<WorkflowEvent>(eventMsg.payload)
                when (val state = event.workflowState) {
                    is WorkflowEvent.WorkflowCompleted -> {
                        if (event.workflowId == mainWorkflowId) {
                            // Main workflow completed - capture the output
                            if (getResult() == null) {
                                setResult(WorkflowTestResult.Success(state.output))
                            }
                        } else {
                            // Child workflow completed - forward to events-in to resume parent
                            eventsSource.send(eventMsg.payload)
                        }
                    }

                    is WorkflowEvent.WorkflowFailed -> {
                        if (event.workflowId == mainWorkflowId) {
                            // Main workflow failed - capture the error
                            if (getResult() == null) {
                                val errorMsg = listOfNotNull(state.error.type, state.error.title)
                                    .joinToString(": ")
                                setResult(
                                    WorkflowTestResult.Failure(
                                        error = errorMsg,
                                        exception = null
                                    )
                                )
                            }
                        } else {
                            // Child workflow failed - forward to events-in to handle error propagation
                            eventsSource.send(eventMsg.payload)
                        }
                    }

                    is WorkflowEvent.WaitStarted -> {
                        // For tests: immediately create resume command (bypass DB persistence)
                        val resumeCommand = InstanceMessage(
                            workflowInfo = event.workflowInfo,
                            workflowState = state.resume()
                        )
                        commandsSource.send(resumeCommand.toJsonString())
                    }

                    is WorkflowEvent.ListenStarted -> {
                        // For tests: listen tasks need CloudEvent delivery which isn't implemented in tests
                        // We would need to mock CloudEvent delivery to test this properly
                        eventsSource.send(eventMsg.payload)
                    }

                    is WorkflowEvent.TaskRetryScheduled -> {
                        // For tests: immediately create resume command (bypass DB persistence)
                        val resumeCommand = InstanceMessage(
                            workflowInfo = event.workflowInfo,
                            workflowState = state.resume()
                        )
                        commandsSource.send(resumeCommand.toJsonString())
                    }

                    is WorkflowEvent.RunWorkflowStarted,
                    is WorkflowEvent.ForkStarted,
                    is WorkflowEvent.ForkBranchCompleted,
                    is WorkflowEvent.ForkBranchFailed -> {
                        // These need database processing via events-in channel
                        eventsSource.send(eventMsg.payload)
                    }

                    is WorkflowEvent.TaskScheduled -> {
                        // This shouldn't appear in events channel
                    }

                    is WorkflowEvent.ActivityStarted -> {
                        // ActivityStarted events shouldn't appear in events channel
                        // Activities are executed inline in the commands channel
                    }
                }
            }
        }

        // Route commands from commands-out back to commands-in
        val commands = commandsSink.received().toList()
        if (commands.isNotEmpty()) {
            commandsSink.clear()
            for (commandMsg in commands) {
                commandsSource.send(commandMsg.payload)
            }
        }
    }

    private suspend fun registerWorkflowInDatabase(yaml: String, namespace: String, name: String, version: String) {
        val ns = WorkflowNamespace(namespace)
        val n = WorkflowName(name)
        val v = WorkflowVersion(version)

        val fullYaml = addDocumentHeader(yaml, namespace, name, version)

        // Store in database
        val existing = definitionRepository.findByNameAndVersion(ns, n, v)
        if (existing != null) {
            definitionRepository.delete(existing)
        }

        val model = DefinitionModel(
            namespace = ns,
            name = n,
            version = v,
            definition = fullYaml
        )
        definitionRepository.insert(model)
    }

    private fun addDocumentHeader(yaml: String, namespace: String, name: String, version: String): String {
        return if (yaml.contains("document:")) {
            yaml
        } else {
            """
            |document:
            |  dsl: '1.0.0'
            |  namespace: $namespace
            |  name: $name
            |  version: '$version'
            |$yaml
            """.trimMargin()
        }
    }
}
