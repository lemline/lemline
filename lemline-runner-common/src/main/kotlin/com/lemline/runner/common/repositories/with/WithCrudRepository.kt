// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.with

import java.sql.Connection

interface WithCrudRepository<T> {
    suspend fun insert(entity: T, connection: Connection? = null): Int
    suspend fun insert(entities: List<T>, connection: Connection? = null): Int
    suspend fun update(entity: T, connection: Connection? = null): Int
    suspend fun update(entities: List<T>, connection: Connection? = null): Int

    /**
     * Writes only [columns] of each entity, and only while [guardColumn] still holds [guardValue]
     * for it. Other columns keep what concurrent writers stored. A column of [keepWhenNull] bound
     * to NULL keeps its stored value too. Returns the entities that were updated.
     */
    suspend fun updateIf(
        entities: List<T>,
        columns: List<String>,
        guardColumn: String,
        guardValue: (T) -> Int,
        keepWhenNull: Set<String> = emptySet(),
        connection: Connection? = null,
    ): List<T>
    suspend fun delete(entity: T, connection: Connection? = null): Int
    suspend fun delete(entities: List<T>, connection: Connection? = null): Int
    suspend fun deleteAll(connection: Connection? = null): Int
    suspend fun listAll(connection: Connection? = null): List<T>
    suspend fun countAll(connection: Connection? = null): Int
}
