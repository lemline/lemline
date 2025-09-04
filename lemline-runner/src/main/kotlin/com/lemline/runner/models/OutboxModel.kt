// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.messaging.LemlineMessage
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.outbox.OutboxRelay
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
 * @see OutboxRelay for the processing logic
 */
@ExperimentalTime
abstract class OutboxModel(open val instanceMessage: InstanceMessage) : LemlineMessage by instanceMessage {

    /**
     * Unique identifier
     */
    abstract val id: IDV7

    /**
     * Current status of the message in the outbox. Possible values:
     * - PENDING: Message is ready to be processed
     * - SENT: Relay has successfully processed this message, and it is safe to delete it from the outbox table
     * - FAILED: Relay has failed to process this message after maximum retry attempts
     *
     * Messages with FAILED status are not processed, neither deleted, and must be manually handled
     *
     * @see OutBoxStatus for possible status values
     */
    abstract var outBoxStatus: OutBoxStatus

    /**
     * The specific timestamp indicating when a message or task is scheduled for processing or execution by the outbox relay.
     * If null, the message or task is not yet scheduled.
     */
    abstract var outboxScheduledFor: Instant?

    /**
     * Timestamp indicating when the message should be processed next by the outbox relay.
     * Used for retry by the outbox relay with exponential backoff.
     *
     * @see OutboxRelay for delay calculation
     */
    abstract var outboxDelayedUntil: Instant?

    /**
     * Number of processing attempts made for this message by the outbox relay.
     * This counter is incremented each time the relay fails.
     * When it reaches the maximum configured attempts, the message is marked as FAILED.
     *
     * @see OutboxRelay.process for retry logic
     */
    abstract var outboxAttemptCount: Int

    /**
     * Class of the last exception that occurred during processing of this message by the outbox relay.
     */
    abstract var outboxErrorClass: String?

    /**
     * Message of the last exception that occurred during processing of this message by the outbox relay.
     */
    abstract var outboxErrorMessage: String?

    /**
     * Stacktrace of the last exception that occurred during processing of this message by the outbox relay.
     */
    abstract var outboxErrorStackTrace: String?
}
