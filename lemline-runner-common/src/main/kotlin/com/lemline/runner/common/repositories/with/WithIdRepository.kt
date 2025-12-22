// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import com.lemline.common.values.IDV7
import java.sql.Connection
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Interface for repositories that support ID-based operations.
 * Implemented by repositories with IdRepository composition.
 */
@ExperimentalSerializationApi
@ExperimentalTime
interface WithIdRepository<T> {

    suspend fun findById(id: IDV7, connection: Connection? = null): T?

    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int
}
