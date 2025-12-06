// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Model representing an active listener waiting for CloudEvents.
 *
 * A listener is created when a workflow reaches a `listen` task and needs
 * to wait for external events before continuing execution.
 *
 * The listener stores:
 * - Workflow identity (namespace, name, version) for matching against cached workflow definitions
 * - Workflow position (for locating the listen task in the workflow tree)
 * - Workflow instance identity (for resuming the workflow when events match)
 * - Single event storage (for ONE/ANY strategies)
 * - Outbox fields (for reliable processing and cleanup)
 *
 * ## Listen Task Configuration
 *
 * Strategy, filters, and readAs are retrieved from the cached workflow definition
 * using (workflowNamespace, workflowName, workflowVersion, workflowPosition).
 *
 * ## Strategies and Event Storage
 *
 * - **ONE**: Single event stored in [event] column, immediate completion
 * - **ANY (without until)**: Single event stored in [event] column, immediate completion
 * - **ANY (with until)**: Events accumulated in `lemline_listener_events` table
 * - **ALL**: Events accumulated in `lemline_listener_events` table (one per filter)
 *
 * ## Correlation
 *
 * Listeners may require events to match specific correlation values.
 * For Mode 2 (first-sets-baseline), the first event sets the baseline
 * and subsequent events must match.
 *
 * @see WorkflowEvent.ListenStarted for the triggering event
 * @see ListenerEventModel for accumulated events (ALL/ANY+until)
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerModel(
    /** Unique identifier for this listener */
    override val id: IDV7,

    /** Workflow instance message containing state for resumption */
    override val instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,

    /** Timestamp when the listener times out (null = no timeout) */
    val timeoutAt: Instant?,

    /** Timestamp when this listener was scheduled for processing */
    override val outboxScheduledFor: Instant,
) : OutboxModel() {

    /** Correlation baseline values (Mode 2: first-sets-baseline), JSON map */
    var correlationValues: String? = null

    /** Single event for ONE/ANY strategies (JSON CloudEvent data) */
    var event: String? = null

    /** Total number of filters for ALL strategy (null for other strategies) */
    var totalFilters: Int? = null
    
    // Outbox fields
    // NOTE: outboxDelayedUntil starts as NULL (waiting state).
    // It gets set to NOW() when a CloudEvent matches, triggering ListenerCompletionOutbox.
    override var outboxDelayedUntil: Instant? = null
    override var outboxAttemptCount: Int = 0
    override var outboxErrorClass: String? = null
    override var outboxErrorMessage: String? = null
    override var outboxErrorStackTrace: String? = null
    override var outboxCompletedAt: Instant? = null
    override var outboxFailedAt: Instant? = null

    companion object Companion
}
