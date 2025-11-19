// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.WithDefiniteWorkflowInfo
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Base class for cleanable pattern models.
 * This abstract class defines the common structure for entities that need cleanup tracking
 * after successful processing (e.g., parent workflows waiting for children, forks waiting for branches).
 *
 * The cleanup pattern works by:
 * 1. When processed successfully, entity is marked with outbox_completed_at timestamp
 * 2. Cleanup schedulers periodically remove old completed records
 *
 * @see com.lemline.runner.cleaner.AbstractCleaner for cleanup infrastructure
 */
@ExperimentalSerializationApi
@ExperimentalTime
sealed class CleanableModel : InstanceModel, WithDefiniteWorkflowInfo {

    abstract val instanceMessage: InstanceMessage<out WorkflowState>

    /**
     * Timestamp when the entity was successfully processed.
     * Used by [com.lemline.runner.cleaner.AbstractCleaner] to determine which records are old enough to delete.
     * - NULL: Entity is still pending/active
     * - NOT NULL: Entity has been processed and is eligible for cleanup
     */
    abstract var outboxCompletedAt: Instant?

    override val workflowState get() = instanceMessage.workflowState

    override val workflowInfo get() = instanceMessage.workflowInfo
}
