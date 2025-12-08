// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.with

import com.lemline.common.values.IDV7
import java.sql.Connection
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Interface for repositories that support outbox operations.
 * Implemented by repositories with outboxOps composition.
 * Extends [WithCleanerRepository] since outbox tables need cleanup.
 *
 * Note: This interface is meant to be implemented by classes that extend Repository,
 * which provides withTransaction and update implementations.
 */
@ExperimentalSerializationApi
@ExperimentalTime
interface WithIdRepository<T> {

    suspend fun findById(id: IDV7, connection: Connection? = null): T?

    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int
}
