// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.RUN_TABLE
import com.lemline.runner.models.RunModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant

/**
 * Repository for managing run messages in the outbox pattern.
 * This repository handles the persistence and retrieval of run messages,
 * which are used to implement run logic for workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see RunModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
internal class RunRepository : OutboxRepository<RunModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RUN_TABLE

    override fun createModel(
        id: String,
        message: String,
        status: OutBoxStatus,
        delayedUntil: Instant,
        attemptCount: Int,
        lastError: String?,
    ) = RunModel(
        id = id,
        message = message,
        status = status,
        delayedUntil = delayedUntil,
        attemptCount = attemptCount,
        lastError = lastError
    )
}
