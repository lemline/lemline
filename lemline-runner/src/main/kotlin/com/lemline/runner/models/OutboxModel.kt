// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutboxProcessor
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Base class for outbox pattern message models.
 * This abstract class defines the common structure for messages stored in an outbox table,
 * implementing the outbox pattern to ensure reliable message delivery.
 *
 * The outbox pattern works by:
 * 1. Storing messages in a database before attempting to send them
 * 2. Processing messages in batches with retry logic
 * 3. Tracking message status and retry attempts
 * 4. Cleaning up successfully processed messages
 *
 * @see OutboxProcessor for the processing logic
 */
@ExperimentalTime
abstract class OutboxModel() : IdModel() {

    abstract val instance: InstanceMessage?

    /**
     * Current status of the message in the outbox. Possible values:
     * - PENDING: Message is ready to be processed
     * - SENT: Message has been successfully processed
     * - FAILED: Message has failed to be processed after maximum retry attempts
     *
     * Messages with FAILED status are not processed, neither deleted, and must be manually handled
     *
     * @see OutBoxStatus for possible status values
     */
    abstract var outBoxStatus: OutBoxStatus

    /**
     * The specific timestamp indicating when a message or task is scheduled for processing or execution.
     * If null, the message or task is not yet scheduled or has no specific scheduled time.
     */
    abstract var outboxScheduledFor: Instant?

    /**
     * Timestamp indicating when the message should be processed next.
     * Used for implementing retry delays with exponential backoff.
     *
     * @see OutboxProcessor for delay calculation
     */
    abstract var outboxDelayedUntil: Instant?

    /**
     * Number of processing attempts made for this message.
     * This counter is incremented each time processing fails.
     * When it reaches the maximum configured attempts, the message is marked as FAILED.
     *
     * @see OutboxProcessor.process for retry logic
     */
    abstract var outboxAttemptCount: Int

    /**
     * Last error message encountered during processing.
     * This field stores the error message from the most recent failed attempt.
     * It helps with debugging and monitoring message processing issues.
     * Null if no errors have occurred yet.
     */
    abstract var outboxLastError: String?

    /**
     * The ID of the workflow.
     */
    val workflowId: UUID by lazy { instance!!.workflowId }

    /**
     * The name of the workflow.
     */
    val workflowName: String by lazy { instance!!.workflowName }

    /**
     * The version of the workflow.
     */
    val workflowVersion: String by lazy { instance!!.workflowVersion }

    /**
     * The current active initial position
     */
    val workflowPosition: NodePosition by lazy { instance!!.workflowPosition.parsed }

    /**
     * A map of the internal initial states (per position)
     */
    val workflowState: WorkflowState by lazy { instance!!.workflowState.parsed }

    /**
     * Indicates the id of the parent model describing the workflow waiting for this workflow completion, if any.
     */
    val parentId: UUID? by lazy { instance!!.parentId }
}
