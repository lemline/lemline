// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.runner.common.activities.TestModeConfiguration
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.messaging.cloudevents.CLOUDEVENTS_OUT_CHANNEL
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.AnalyticsWorkflowResultAwaiter
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.delay
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Workflow test executor that uses real message brokers (Kafka/RabbitMQ).
 *
 * Relies on broker loopback configuration (same topic/queue for in/out channels)
 * for automatic message routing. No manual routing needed.
 *
 * For listen task tests, this executor:
 * 1. Starts the workflow
 * 2. Waits for listener registration in the database
 * 3. Sends CloudEvents to the broker
 * 4. Waits for workflow completion
 *
 * Workflow completion is detected from analytics-ingested lifecycle events, ensuring
 * full-chain verification through the broker and analytics sink.
 */
@Singleton
internal class BrokerWorkflowTestExecutor : AbstractWorkflowTestExecutor() {

    private val logger = logger()

    companion object {
        /** Delay for database writes to complete after listener registration */
        private const val DB_COMMIT_DELAY_MS = 50L

        /** Delay after sending CloudEvents for outbox processing (must exceed outbox poll interval) */
        private const val OUTBOX_PROCESSING_DELAY_MS = 1_500L
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
    lateinit var commandEmitter: WorkflowCommandEmitter

    // Use Instance for optional injection since CloudEvents channel may not be configured
    @Inject
    @Channel(CLOUDEVENTS_OUT_CHANNEL)
    lateinit var cloudEventsEmitterInstance: Instance<MutinyEmitter<String>>

    override suspend fun sendInitialCommand(message: InstanceMessage<out WorkflowCommand>) {
        commandEmitter.send(message)
    }

    override suspend fun sendCloudEventPayload(payload: String) {
        if (cloudEventsEmitterInstance.isUnsatisfied) {
            logger.warn { "CloudEvents emitter not available - CloudEvents will not be sent" }
            return
        }
        cloudEventsEmitterInstance.get().sendMessage(Message.of(payload)).awaitSuspending()
    }

    override suspend fun awaitCompletion(workflowId: WorkflowId, timeoutSeconds: Long): WorkflowTestResult {
        // Broker handles routing automatically - just wait for lifecycle events from broker
        return awaitLifecycleResult(workflowId, timeoutSeconds)
    }

    /**
     * Add delay after listener registration for DB commit propagation.
     */
    override suspend fun onListenerRegistered() {
        delay(DB_COMMIT_DELAY_MS)
    }

    /**
     * Add delay after sending CloudEvents for outbox processing.
     */
    override suspend fun onCloudEventsSent() {
        delay(OUTBOX_PROCESSING_DELAY_MS)
    }
}
