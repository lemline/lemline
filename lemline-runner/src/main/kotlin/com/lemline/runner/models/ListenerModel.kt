// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy as CoreListenStrategy
import com.lemline.core.processors.UntilCondition
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Database representation of listen strategy.
 *
 * This enum distinguishes between all possible listen task configurations:
 * - `ONE`: Single event, immediate completion
 * - `ANY`: First matching event from multiple filters, immediate completion
 * - `ANY_UNTIL_EXPR`: Accumulate events until expression condition is met
 * - `ANY_UNTIL_EVENT`: Accumulate events until termination event is received
 * - `ALL`: Wait for one event per filter
 */
enum class ListenerStrategy {
    /** Wait for a single event matching the filter */
    ONE,

    /** Wait for first event matching any filter, immediate completion */
    ANY,

    /** Accumulate events until expression condition is met */
    ANY_UNTIL_EXPR,

    /** Accumulate events until termination event is received */
    ANY_UNTIL_EVENT,

    /** Wait for one event per filter */
    ALL;

    companion object {
        /**
         * Converts from core ListenStrategy + until condition to database ListenerStrategy.
         */
        fun from(config: ListenConfig): ListenerStrategy = when (config.strategy) {
            CoreListenStrategy.ONE -> ONE
            CoreListenStrategy.ANY -> when (config.until) {
                is UntilCondition.Expression -> ANY_UNTIL_EXPR
                is UntilCondition.Event -> ANY_UNTIL_EVENT
                null -> ANY
            }

            CoreListenStrategy.ALL -> ALL
        }
    }
}

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

    /** Listen strategy: ONE, ANY, ANY_UNTIL, ALL */
    val strategy: ListenerStrategy,

    /** Timestamp when the listener times out (null = no timeout) */
    val timeoutAt: Instant?,

    /** Timestamp when this listener was scheduled for processing */
    override val outboxScheduledFor: Instant,
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

    /** Correlation baseline values (Mode 2: first-sets-baseline), JSON map */
    var correlationValues: String? = null

    /** Single event for ONE/ANY strategies (JSON CloudEvent data) */
    var event: String? = null

    /** Total number of filters for ALL strategy (null for other strategies) */
    var filtersCount: Int? = null

    // Foreach fields
    /** TRUE if listener has foreach.do configured */
    var hasForeach: Boolean = false

    /** Current foreach iteration index (0-based) */
    var foreachCurrentIndex: Int = 0

    /** TRUE when foreach.do is executing for an event */
    var foreachProcessing: Boolean = false

    /** TRUE when completion criteria met (set by CloudEventHandler) */
    var listenerCompleted: Boolean = false

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
    override var cleanupAfter: Instant? = null

    companion object Companion
}
