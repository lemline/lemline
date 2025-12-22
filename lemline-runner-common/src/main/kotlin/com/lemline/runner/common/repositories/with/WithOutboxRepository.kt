// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import com.lemline.runner.common.models.WithOutbox
import java.sql.Connection
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Interface for repositories that support outbox operations.
 * Implemented by repositories with outboxOps composition.
 *
 * Note: This interface is meant to be implemented by classes that extend Repository,
 * which provides withTransaction and update implementations.
 */
@ExperimentalSerializationApi
@ExperimentalTime
interface WithOutboxRepository<T : WithOutbox> {
    /**
     * Find entities to process from the outbox.
     */
    suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?): List<T>
}
