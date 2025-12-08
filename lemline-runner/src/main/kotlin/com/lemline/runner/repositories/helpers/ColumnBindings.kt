// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.helpers

import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Represents a column binding for mapping entity properties to SQL prepared statement parameters.
 *
 * @param T The entity type
 * @param bind Function to bind the entity's property value to the prepared statement at a given index
 */
data class ColumnBinding<T>(
    val bind: (PreparedStatement, T, Int) -> Unit
)

/**
 * Container for column bindings that define how entity properties map to database columns.
 *
 * @param T The entity type
 * @param bindings Map of column names to their binding functions
 * @param keyColumns List of column names that are part of the primary key
 */
class ColumnBindings<T>(
    val bindings: Map<String, ColumnBinding<T>>,
    val keyColumns: List<String>
) {
    /**
     * All column names in the binding definition
     */
    val allColumns: Set<String> get() = bindings.keys

    /**
     * Columns that can be updated (all columns except key columns and created_at)
     */
    val updatableColumns: List<String> by lazy {
        bindings.keys.filter { !keyColumns.contains(it) && it != "created_at" }
    }

    /**
     * Binds all columns for an INSERT statement
     */
    fun bindInsert(stmt: PreparedStatement, entity: T, additionalColumns: List<String> = emptyList()) {
        val allCols = bindings.keys.toList() + additionalColumns
        allCols.forEachIndexed { index, column ->
            when {
                bindings.containsKey(column) -> bindings[column]!!.bind(stmt, entity, index + 1)
                column == "created_at" -> stmt.setTimestamp(index + 1, Timestamp.from(java.time.Instant.now()))
                column == "updated_at" -> stmt.setNull(index + 1, Types.TIMESTAMP)
                else -> error("No handler for column $column")
            }
        }
    }

    /**
     * Binds updatable columns for an UPDATE statement
     */
    fun bindUpdate(stmt: PreparedStatement, entity: T, additionalColumns: List<String> = emptyList()) {
        val updateCols = updatableColumns + additionalColumns.filter { !keyColumns.contains(it) && it != "created_at" }
        updateCols.forEachIndexed { index, column ->
            when (column) {
                "updated_at" -> stmt.setTimestamp(index + 1, Timestamp.from(java.time.Instant.now()))
                else -> bindings[column]!!.bind(stmt, entity, index + 1)
            }
        }
    }

    /**
     * Binds key columns for WHERE clauses
     */
    fun bindKeys(stmt: PreparedStatement, entity: T, startIndex: Int = 0) {
        keyColumns.forEachIndexed { index, column ->
            bindings[column]!!.bind(stmt, entity, startIndex + index + 1)
        }
    }
}

/**
 * Builder for creating ColumnBindings using a DSL.
 *
 * @param T The entity type
 */
class ColumnBindingsBuilder<T> {
    private val bindings = mutableMapOf<String, ColumnBinding<T>>()
    private val keys = mutableListOf<String>()

    /**
     * Defines a regular column binding
     */
    fun column(name: String, bind: (PreparedStatement, T, Int) -> Unit) {
        bindings[name] = ColumnBinding(bind)
    }

    /**
     * Defines a key column binding (part of primary key)
     */
    fun key(name: String, bind: (PreparedStatement, T, Int) -> Unit) {
        bindings[name] = ColumnBinding(bind)
        keys.add(name)
    }

    /**
     * Builds the ColumnBindings instance
     */
    fun build() = ColumnBindings(bindings, keys)
}

/**
 * DSL function to create ColumnBindings
 *
 * Example usage:
 * ```kotlin
 * val columns = columnBindings<MyModel> {
 *     key("id") { stmt, entity, idx -> stmt.setObject(idx, entity.id) }
 *     column("name") { stmt, entity, idx -> stmt.setString(idx, entity.name) }
 *     column("created_at") { stmt, entity, idx -> stmt.setTimestamp(idx, entity.createdAt) }
 * }
 * ```
 */
fun <T> columnBindings(init: ColumnBindingsBuilder<T>.() -> Unit): ColumnBindings<T> =
    ColumnBindingsBuilder<T>().apply(init).build()

// Common binding helpers

/**
 * Creates a binding for nullable String columns
 */
fun <T> stringBinding(getter: (T) -> String?): (PreparedStatement, T, Int) -> Unit = { stmt, entity, idx ->
    stmt.setString(idx, getter(entity))
}

/**
 * Creates a binding for non-null String columns
 */
fun <T> requiredStringBinding(getter: (T) -> String): (PreparedStatement, T, Int) -> Unit = { stmt, entity, idx ->
    stmt.setString(idx, getter(entity))
}

/**
 * Creates a binding for nullable Int columns
 */
fun <T> intBinding(getter: (T) -> Int?): (PreparedStatement, T, Int) -> Unit = { stmt, entity, idx ->
    getter(entity)?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
}

/**
 * Creates a binding for non-null Int columns
 */
fun <T> requiredIntBinding(getter: (T) -> Int): (PreparedStatement, T, Int) -> Unit = { stmt, entity, idx ->
    stmt.setInt(idx, getter(entity))
}

/**
 * Creates a binding for nullable Instant columns (stored as TIMESTAMP)
 */
@OptIn(ExperimentalTime::class)
fun <T> instantBinding(getter: (T) -> Instant?): (PreparedStatement, T, Int) -> Unit = { stmt, entity, idx ->
    getter(entity)?.let { stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant())) }
        ?: stmt.setNull(idx, Types.TIMESTAMP)
}
