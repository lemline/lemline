// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.lifecycleevents.CloudEventLifecycleHook
import com.lemline.core.lifecycleevents.LifecycleEventEmitter
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.messaging.InstanceMessage
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Persists lifecycle events that must become observable even if the accepting process crashes
 * right after its commit, or if no worker ever consumes the command.
 *
 * Callers invoke it inside the transaction that accepts a command; once that transaction has
 * committed they may ask [CreationEventOutbox.processNow] to publish the rows at once (the scheduled
 * relay remains the recovery path). The command and its events are relayed independently: an
 * unavailable lifecycle channel never blocks execution, and a stopped worker pool never blocks
 * the creation events.
 *
 * Nothing is enqueued when lifecycle events, or this outbox (`lemline.outbox.creation-events.enabled`),
 * are disabled: no creation events are produced at all then.
 */
@ApplicationScoped
class CreationEventEnqueuer {

    @Inject
    lateinit var repository: CreationEventRepository

    @Inject
    lateinit var publisher: LifecycleEventPublisher

    /** Same switch as the relay: rows are never written when nothing would publish them. */
    @Inject
    var outboxConfig: CreationEventOutboxConfig? = null

    /** Clock used for the rows' outbox scheduling (not for event times, which are given). */
    var clock: Clock = Clock.System

    val enabled: Boolean get() = publisher.enabled && (outboxConfig?.enabled ?: true)

    /**
     * Enqueues `workflow.created` and the initial `/do` `task.created` of an accepted initial
     * command, both stamped with its [WorkflowCommand.ResumeFromTask.createdAt].
     *
     * @return the IDs of the enqueued events (also when they already existed)
     */
    suspend fun enqueueCreation(
        message: InstanceMessage<WorkflowCommand.ResumeFromTask>,
        connection: Connection,
    ): List<IDV7> = enqueue(message.workflowInfo, connection) { hook ->
        StepByStepOrchestrator.emitCreationEvents(message.workflowInfo, message.workflowState, hook)
    }

    /** Enqueues the `task.created` of a task whose command is dispatched outside a worker (fork branches). */
    suspend fun enqueueTaskCreated(
        workflowInfo: WorkflowInfo,
        nodeStack: NodeStack,
        nodePosition: NodePosition,
        input: JsonNode,
        createdAt: Instant,
        connection: Connection,
    ): List<IDV7> = enqueue(workflowInfo, connection) { hook ->
        hook.onTaskCreated(workflowInfo, nodeStack, nodePosition, input, createdAt)
    }

    private suspend fun enqueue(
        workflowInfo: WorkflowInfo,
        connection: Connection,
        build: suspend (LifecycleEventHook) -> Unit,
    ): List<IDV7> {
        if (!enabled) return emptyList()
        val events = mutableListOf<CloudEvent>()
        build(CloudEventLifecycleHook(object : LifecycleEventEmitter {
            override suspend fun emit(cloudEvent: CloudEvent) {
                events.add(cloudEvent)
            }
        }))
        val now = clock.now()
        val rows = events.map { event ->
            CreationEventModel(
                id = IDV7.from(event.id),
                workflowId = com.lemline.common.values.WorkflowId(IDV7.from(event.getExtension("lemlineworkflowid").toString())),
                workflowNamespace = workflowInfo.namespace.toString(),
                eventType = event.type,
                eventTime = requireNotNull(event.time) { "Lifecycle event without time" }.toInstant().toKotlinInstant(),
                eventPayload = serialize(event),
                outboxScheduledFor = now,
            )
        }
        // Duplicates (a redelivered acceptance, a retried start) are ignored by the primary key.
        repository.insert(rows, connection)
        return rows.map { it.id }
    }

    companion object {
        private val jsonFormat = JsonFormat()

        fun serialize(event: CloudEvent): String = String(jsonFormat.serialize(event), Charsets.UTF_8)
    }
}
