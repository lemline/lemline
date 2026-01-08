// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.repositories.helpers

import java.sql.PreparedStatement

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
 * @param keyColumns Set of column names that are part of the primary key (ordered by insertion)
 * @param nullableKeyColumns Set of key columns that can contain NULL values
 */
class ColumnBindings<T>(
    val bindings: Map<String, ColumnBinding<T>>,
    val keyColumns: Set<String>,
    val nullableKeyColumns: Set<String> = emptySet()
) {
    /**
     * All column names in the binding definition
     */
    val allColumns: Set<String> get() = bindings.keys

}

/**
 * Builder for creating ColumnBindings using a DSL.
 *
 * @param T The entity type
 */
class ColumnBindingsBuilder<T> {
    private val bindings = mutableMapOf<String, ColumnBinding<T>>()
    private val keys = mutableSetOf<String>()
    private val nullableKeys = mutableSetOf<String>()

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
     * Defines a nullable key column binding (part of primary key, can be NULL).
     * Uses NULL-safe comparison in WHERE clauses.
     */
    fun nullableKey(name: String, bind: (PreparedStatement, T, Int) -> Unit) {
        bindings[name] = ColumnBinding(bind)
        keys.add(name)
        nullableKeys.add(name)
    }

    /**
     * Builds the ColumnBindings instance
     */
    fun build() = ColumnBindings(bindings, keys, nullableKeys)
}
