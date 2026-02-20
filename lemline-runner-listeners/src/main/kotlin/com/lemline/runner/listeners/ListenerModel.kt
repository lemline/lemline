// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.time.Instant

/**
 * Model representing an active listener waiting for CloudEvents.
 *
 * A listener is created when a workflow reaches a `listen` task and needs
 * to wait for external events before continuing execution.
 *
 * ## Simplified Architecture
 *
 * All events (for ALL strategies) are stored in `lemline_listener_events` table.
 * State tracking uses standard outbox columns + `completed_at` for completion.
 *
 * The listener stores:
 * - Workflow identity (namespace, name, version) for matching against cached workflow definitions
 * - Workflow position (for locating the listen task in the workflow tree)
 * - Workflow instance identity (for resuming the workflow when events match)
 * - Configuration flags (hasForeach, hasUntil, filtersCount)
 * - Outbox fields (for reliable processing and cleanup)
 *
 * ## Listen Task Configuration
 *
 * Strategy, filters, and readAs are retrieved from the cached workflow definition
 * using (workflowNamespace, workflowName, workflowVersion, workflowPosition).
 *
 * ## Completion Flow
 *
 * 1. CloudEvents arrive → INSERT into listener_events
 * 2. When completion criteria met: completed_at is set (listener stops collecting events)
 * 3. If hasForeach: ListenerForeachOutbox processes events sequentially
 * 4. When all foreach done (or no foreach): outbox_delayed_until is set
 * 5. ListenerCompletionOutbox picks up listener and emits resume command
 *
 * ## Correlation
 *
 * Listeners may require events to match specific correlation values.
 * For Mode 2 (first-sets-baseline), the first event sets the baseline
 * and subsequent events must match.
 *
 * @see WorkflowEvent.ListenStarted for the triggering event
 * @see ListenerEventModel for accumulated events
 */
data class ListenerModel(
    override var instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,
    override val id: IDV7 = instanceMessage.workflowState.nodeStack.listenerId(),
    val listenerStrategy: ListenerStrategy = ListenerStrategy.from(instanceMessage.workflowState.config),
    val timeoutAt: Instant? = instanceMessage.workflowState.config.timeoutAt,
    var filtersCount: Int? = null,
    var hasUntil: Boolean = false,
    var untilExpression: String? = null,
    var hasForeach: Boolean = false,
    var correlationValues: String? = null,
    var closedAt: Instant? = null,
    override var outboxScheduledFor: Instant? = null,
    override var outboxDelayedUntil: Instant? = null,
    override var outboxAttemptCount: Int = 0,
    override var outboxErrorClass: String? = null,
    override var outboxErrorMessage: String? = null,
    override var outboxErrorStackTrace: String? = null,
    override var outboxCompletedAt: Instant? = null,
    override var outboxFailedAt: Instant? = null,
    override var cleanupAfter: Instant? = null,
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

    val readAs: ListenAndReadAs by lazy {
        val listenTasks = WorkflowCache.getListenTasks(workflowInfo)
        val listenTask = listenTasks.find { it.nodePosition == nodePosition }
        listenTask?.readAs ?: ListenAndReadAs.DATA
    }

    companion object Companion
}
