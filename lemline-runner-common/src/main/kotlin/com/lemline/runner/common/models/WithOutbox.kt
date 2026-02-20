// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.models

import kotlin.time.Instant

/**
 * Base class for outbox pattern message models.
 * This interface extends [WithCleanup] and adds retry/error tracking for the outbox pattern.
 *
 * OutboxModel entities are processed via scheduled relay with retry logic.
 * They implement reliable message delivery through:
 * 1. Storing messages in a database before attempting to send them
 * 2. Processing messages in batches with retry logic
 * 3. Tracking retry attempts and error details
 * 4. Cleanup via [WithCleanup.cleanupAfter] timestamp
 *
 * States are tracked via nullable timestamps:
 * - Pending: outbox_completed_at IS NULL AND outbox_failed_at IS NULL
 * - Completed: outbox_completed_at IS NOT NULL
 * - Failed: outbox_failed_at IS NOT NULL
 *
 * @see com.lemline.runner.common.outbox.AbstractOutbox for the processing logic
 * @see WithCleanup for cleanup tracking
 */
interface WithOutbox {

    /**
     * Timestamp when the outbox processing completed successfully.
     * - NULL: Processing is still pending or has failed
     * - NOT NULL: Processing completed successfully
     */
    var outboxCompletedAt: Instant?

    /**
     * Original/intended scheduled time for this message.
     * Provides observability - shows when this was supposed to run vs actual processing time.
     * - For Wait/Retry: Original scheduled time (doesn't change with retries)
     * - For Schedule: Current scheduled execution time (updated each cycle)
     */
    val outboxScheduledFor: Instant?

    /**
     * Timestamp indicating when the message should be processed next by the outbox relay.
     * Used for retry scheduling with exponential backoff.
     * - NULL: Process immediately
     * - NOT NULL: Process after this timestamp
     *
     * @see com.lemline.runner.common.outbox.AbstractOutbox for delay calculation
     */
    var outboxDelayedUntil: Instant?

    /**
     * Number of processing attempts made for this message by the outbox relay.
     * This counter is incremented each time the relay fails.
     * When it reaches the maximum configured attempts, outbox_failed_at is set.
     *
     * @see com.lemline.runner.common.outbox.AbstractOutbox for retry logic
     */
    var outboxAttemptCount: Int

    /**
     * Class of the last exception that occurred during processing of this message by the outbox relay.
     */
    var outboxErrorClass: String?

    /**
     * Message of the last exception that occurred during processing of this message by the outbox relay.
     */
    var outboxErrorMessage: String?

    /**
     * Stacktrace of the last exception that occurred during processing of this message by the outbox relay.
     */
    var outboxErrorStackTrace: String?

    /**
     * Timestamp when the outbox processing permanently failed (max retries reached).
     * - NULL: Not failed (either pending or completed)
     * - NOT NULL: Failed after max retry attempts, requires manual intervention
     */
    var outboxFailedAt: Instant?
}
