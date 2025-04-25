// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.models.WAIT_TABLE
import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Repository for managing wait messages in the outbox pattern.
 * This repository handles the persistence and retrieval of wait messages,
 * which are used to implement delayed execution in workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see WaitModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
internal class WaitRepository : OutboxRepository<WaitModel>() {
    override val tableName: String = WAIT_TABLE
    override val entityClass: Class<WaitModel> = WaitModel::class.java
}
