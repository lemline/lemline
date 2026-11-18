// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.Instant

/**
 * A lifecycle CloudEvent whose publication must survive a crash: the creation events of a
 * workflow instance (`workflow.created`, initial `/do` `task.created`) and of fork branches.
 *
 * The row is inserted in the transaction that accepts the command, so the event exists if and
 * only if the command was accepted. The event is fully built and serialized at that time: its
 * ID (the deterministic CloudEvent ID, also the primary key) and its time are frozen and never
 * recomputed by relay retries.
 */
data class CreationEventModel(
    /** The CloudEvent ID: duplicate enqueues of the same event are ignored. */
    override val id: IDV7,
    val workflowId: WorkflowId,
    val workflowNamespace: String,
    /** CloudEvent type, e.g. `io.lemline.workflow.created.v1` (for inspection). */
    val eventType: String,
    /** CloudEvent time, frozen at enqueue (for inspection). */
    val eventTime: Instant,
    /** The CloudEvent, serialized as CloudEvents JSON. */
    val eventPayload: String,
    override val outboxScheduledFor: Instant?,
) : WithId, WithOutbox, WithCleanup {

    override var outboxDelayedUntil: Instant? = outboxScheduledFor

    override var outboxAttemptCount: Int = 0

    override var outboxErrorClass: String? = null

    override var outboxErrorMessage: String? = null

    override var outboxErrorStackTrace: String? = null

    override var outboxCompletedAt: Instant? = null

    override var outboxFailedAt: Instant? = null

    override var cleanupAfter: Instant? = null

    override fun toString() =
        "CreationEventModel(id=$id, workflowId=$workflowId, eventType=$eventType, eventTime=$eventTime, " +
            "attempts=$outboxAttemptCount, completedAt=$outboxCompletedAt, failedAt=$outboxFailedAt)"
}
