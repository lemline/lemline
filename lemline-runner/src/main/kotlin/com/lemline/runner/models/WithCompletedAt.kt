// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Interface for entities that track completion status.
 *
 * Used by entities like ParentModel and ForkModel that need to track
 * when they have completed their purpose (e.g., child workflow finished).
 *
 * This is separate from:
 * - [WithOutbox.outboxCompletedAt] which tracks outbox processing completion
 * - [WithCleanup.cleanupAfter] which tracks when cleanup should happen
 *
 * @see ParentModel
 * @see ForkModel
 */
@ExperimentalSerializationApi
@ExperimentalTime
sealed interface WithCompletedAt {

    /**
     * Timestamp when this entity completed its purpose.
     * - NULL: Entity is still active/waiting
     * - NOT NULL: Entity has completed (e.g., child workflow finished)
     */
    var completedAt: Instant?
}
