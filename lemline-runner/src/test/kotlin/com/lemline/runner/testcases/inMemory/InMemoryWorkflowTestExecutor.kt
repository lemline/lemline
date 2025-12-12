// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.TestLifecycleEventListener
import com.lemline.runner.testcases.bases.AbstractWorkflowTestExecutor
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi

private const val LIFECYCLEEVENTS_OUT_CHANNEL = "lifecycleevents-out"
private const val LIFECYCLEEVENTS_IN_CHANNEL = "lifecycleevents-in"

/**
 * Workflow test executor using in-memory channels with manual message routing.
 *
 * SmallRye's InMemoryConnector requires manual loopback routing:
 * - commands-out → commands-in
 * - events-out → events-in
 * - lifecycleevents-out → lifecycleevents-in
 *
 * Lifecycle CloudEvents are captured via [TestLifecycleEventListener] which subscribes
 * to the in-memory channel, verifying that events flow through the messaging infrastructure.
 */
@Singleton
@ExperimentalTime
@ExperimentalSerializationApi
internal class InMemoryWorkflowTestExecutor : AbstractWorkflowTestExecutor() {

    @Inject
    override lateinit var definitionRepository: DefinitionRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var lifecycleListener: TestLifecycleEventListener

    @Inject
    override lateinit var starter: Starter

    @Inject
    override lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    // Channel accessors
    private val commandsIn get() = connector.source<String>(COMMANDS_IN_CHANNEL)
    private val commandsOut get() = connector.sink<String>(COMMANDS_OUT_CHANNEL)
    private val eventsIn get() = connector.source<String>(EVENTS_IN_CHANNEL)
    private val eventsOut get() = connector.sink<String>(EVENTS_OUT_CHANNEL)
    private val lifecycleEventsIn get() = connector.source<String>(LIFECYCLEEVENTS_IN_CHANNEL)
    private val lifecycleEventsOut get() = connector.sink<String>(LIFECYCLEEVENTS_OUT_CHANNEL)

    override suspend fun sendInitialCommand(message: InstanceMessage<out WorkflowCommand>) {
        commandsOut.clear()
        eventsOut.clear()
        lifecycleEventsOut.clear()
        commandsIn.send(message.toJsonString())
    }

    override suspend fun awaitCompletion(workflowId: WorkflowId, timeoutSeconds: Long): WorkflowTestResult {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000

        try {
            while (System.currentTimeMillis() < deadline) {
                routeMessages()

                try {
                    return lifecycleListener.awaitWorkflowResult(workflowId, timeout = 0.seconds)
                } catch (_: TimeoutException) {
                    // Not yet completed
                }

                delay(20)
            }

            return WorkflowTestResult.Failure(
                error = "Workflow did not complete within $timeoutSeconds seconds. " +
                    "Captured events: ${lifecycleListener.summary()}",
                exception = TimeoutException("Workflow execution timeout")
            )
        } finally {
            commandsOut.clear()
            eventsOut.clear()
            lifecycleEventsOut.clear()
        }
    }

    /** Routes messages from output sinks to input sources (manual loopback). */
    private fun routeMessages() {
        commandsOut.received().toList().also { commandsOut.clear() }
            .forEach { commandsIn.send(it.payload) }

        eventsOut.received().toList().also { eventsOut.clear() }
            .forEach { eventsIn.send(it.payload) }

        // Route lifecycle events to the listener
        lifecycleEventsOut.received().toList().also { lifecycleEventsOut.clear() }
            .forEach { lifecycleEventsIn.send(it.payload) }
    }
}
