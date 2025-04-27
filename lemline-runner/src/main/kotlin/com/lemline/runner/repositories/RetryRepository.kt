// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.RETRY_TABLE
import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Repository for managing retry messages in the outbox pattern.
 * This repository handles the persistence and retrieval of retry messages,
 * which are used to implement retry logic for failed operations in workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see RetryModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
internal class RetryRepository : OutboxRepository<RetryModel> {
    override val tableName: String = RETRY_TABLE
    override val entityClass: Class<RetryModel> = RetryModel::class.java
}
