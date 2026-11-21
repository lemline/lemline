// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import java.sql.Connection

interface WithCrudRepository<T> {
    suspend fun insert(entity: T, connection: Connection? = null): Int
    suspend fun insert(entities: List<T>, connection: Connection? = null): Int
    suspend fun update(entity: T, connection: Connection? = null): Int
    suspend fun update(entities: List<T>, connection: Connection? = null): Int

    /**
     * Updates each entity only while [guardColumn] still holds [guardValue] for it.
     * Returns the entities that were updated.
     */
    suspend fun updateIf(
        entities: List<T>,
        guardColumn: String,
        guardValue: (T) -> Int,
        connection: Connection? = null,
    ): List<T>
    suspend fun delete(entity: T, connection: Connection? = null): Int
    suspend fun delete(entities: List<T>, connection: Connection? = null): Int
    suspend fun deleteAll(connection: Connection? = null): Int
    suspend fun listAll(connection: Connection? = null): List<T>
    suspend fun countAll(connection: Connection? = null): Int
}
