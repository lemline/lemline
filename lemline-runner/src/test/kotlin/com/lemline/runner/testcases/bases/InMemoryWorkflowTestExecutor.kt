// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.runner.common.activities.TestModeConfiguration
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_OUT_CHANNEL
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.messaging.MetaData
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.AnalyticsWorkflowResultAwaiter
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.delay
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata

/**
 * Workflow test executor using in-memory channels with manual message routing.
 *
 * SmallRye's InMemoryConnector requires manual loopback routing:
 * - commands-out → commands-in
 * - events-out → events-in
 * - lifecycleevents-out → lifecycleevents-in
 *
 * Workflow completion is detected from analytics-ingested lifecycle events, ensuring
 * full-chain verification even with in-memory message routing.
 */
@Singleton
internal class InMemoryWorkflowTestExecutor : AbstractWorkflowTestExecutor() {

    private val logger = logger()

    companion object {
        /** Delay for workflow completion polling */
        private const val COMPLETION_POLL_INTERVAL_MS = 20L
    }

    @Inject
    override lateinit var definitionRepository: DefinitionRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var workflowResultAwaiter: AnalyticsWorkflowResultAwaiter

    @Inject
    override lateinit var starter: Starter

    @Inject
    override lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    override lateinit var listenerRepository: ListenerRepository

    @Inject
    override lateinit var testModeConfiguration: TestModeConfiguration

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
    private val cloudEventsIn get() = connector.source<Message<String>>(CLOUDEVENTS_IN_CHANNEL)

    override suspend fun sendInitialCommand(message: InstanceMessage<out WorkflowCommand>) {
        commandsOut.clear()
        eventsOut.clear()
        lifecycleEventsOut.clear()
        commandsIn.send(message.toTransportPayload())
    }

    /**
     * Spacing between CloudEvent sends. The broker test resources set the system property
     * JVM-wide (RabbitMQ: 50ms, PGMQ: 500ms); standalone in-memory runs need an explicit
     * default, otherwise back-to-back sends race in the concurrent cloudevents subscriber
     * and event accumulation order becomes non-deterministic.
     */
    override val cloudEventSendIntervalMs: Long? by lazy {
        System.getProperty("test.workflow.cloudevent-send-interval-ms")?.toLongOrNull() ?: 50L
    }

    override suspend fun sendCloudEventPayload(payload: String, namespace: String) {
        // The in-memory arrival-namespace resolver needs MetaData.namespace when the runner
        // follows several namespaces (InMemoryConnector sends a Message argument as-is)
        val metaData = MetaData(messageId = IDV7.random(), namespace = namespace)
        cloudEventsIn.send(Message.of(payload, Metadata.of(metaData)))
    }

    override suspend fun awaitCompletion(workflowId: WorkflowId, timeoutSeconds: Long): WorkflowTestResult {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000

        try {
            while (System.currentTimeMillis() < deadline) {
                routeMessages()

                workflowResultAwaiter.findWorkflowResult(workflowId)?.let { return it }

                delay(COMPLETION_POLL_INTERVAL_MS)
            }

            return WorkflowTestResult.Failure(
                error = "Workflow did not complete within $timeoutSeconds seconds. " +
                    "Captured events: ${workflowResultAwaiter.summary(workflowId)}",
                exception = java.util.concurrent.TimeoutException("Workflow execution timeout")
            )
        } finally {
            commandsOut.clear()
            eventsOut.clear()
            lifecycleEventsOut.clear()
        }
    }

    /**
     * Override to route messages while waiting for listener registration.
     */
    override suspend fun waitForListenerRegistration(workflowId: WorkflowId): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < listenerWaitTimeoutMs) {
            // Route messages to allow workflow to progress to the listen task
            routeMessages()

            val listener = listenerRepository.listAll().firstOrNull { it.workflowId == workflowId }
            if (listener != null) {
                logger.debug { "Listener found for workflow $workflowId after ${System.currentTimeMillis() - startTime}ms" }
                return true
            }
            delay(listenerPollIntervalMs)
        }

        logger.warn { "Timeout waiting for listener registration for workflow $workflowId" }
        return false
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
