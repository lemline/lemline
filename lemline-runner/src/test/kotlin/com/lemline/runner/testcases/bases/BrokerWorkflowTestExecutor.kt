// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.TestLifecycleEventListener
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Workflow test executor that uses real message brokers (Kafka/RabbitMQ).
 *
 * Relies on broker loopback configuration (same topic/queue for in/out channels)
 * for automatic message routing. No manual routing needed.
 *
 * Lifecycle CloudEvents are captured via [TestLifecycleEventListener] which subscribes
 * to the broker, verifying that events actually flow through the messaging infrastructure.
 */
@Singleton
@ExperimentalTime
@ExperimentalSerializationApi
internal class BrokerWorkflowTestExecutor : AbstractWorkflowTestExecutor() {

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
    lateinit var commandEmitter: WorkflowCommandEmitter

    override suspend fun sendInitialCommand(message: InstanceMessage<out WorkflowCommand>) {
        commandEmitter.send(message)
    }

    override suspend fun awaitCompletion(workflowId: WorkflowId, timeoutSeconds: Long): WorkflowTestResult {
        // Broker handles routing automatically - just wait for lifecycle events from broker
        return awaitLifecycleResult(workflowId, timeoutSeconds)
    }
}
