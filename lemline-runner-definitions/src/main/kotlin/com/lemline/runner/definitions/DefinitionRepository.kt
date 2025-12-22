// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CrudRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet

const val DEFINITION_TABLE = "lemline_definitions"

/**
 * Repository for managing workflow definitions.
 * Uses composition pattern with column bindings.
 *
 * @see DefinitionModel for the entity model
 */
@ApplicationScoped
class DefinitionRepository : CrudRepository<DefinitionModel>() {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = DEFINITION_TABLE

    companion object {
        // Local column constants matching lemline_definitions table schema
        internal const val NAMESPACE_COLUMN = "namespace"
        internal const val NAME_COLUMN = "name"
        internal const val VERSION_COLUMN = "version"
        internal const val DEFINITION_COLUMN = "definition"
    }

    override val columns: ColumnBindings<DefinitionModel> by lazy {
        ColumnBindingsBuilder<DefinitionModel>().apply {
            // Composite key columns (namespace, name, version)
            key(NAMESPACE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.namespace.toString())
            }
            key(NAME_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.name.toString())
            }
            key(VERSION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.version.toString())
            }

            // Other columns
            column(DEFINITION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.definition)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet): DefinitionModel = DefinitionModel(
        namespace = WorkflowNamespace(rs.getString(NAMESPACE_COLUMN)),
        name = WorkflowName(rs.getString(NAME_COLUMN)),
        version = WorkflowVersion(rs.getString(VERSION_COLUMN)),
        definition = rs.getString(DEFINITION_COLUMN)
    )

    /**
     * Retrieves all workflow definitions within the specified namespace.
     */
    suspend fun listAllInNamespace(
        namespace: WorkflowNamespace,
        connection: Connection? = null
    ): List<DefinitionModel> = databaseConfig.withConnection(connection) {
        it.prepareStatement(listAllInNamespaceSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val listAllInNamespaceSql by lazy { "SELECT * FROM $tableName WHERE $NAMESPACE_COLUMN = ?" }

    /**
     * Deletes all workflows belonging to the given namespace.
     */
    suspend fun deleteAllInNamespace(namespace: WorkflowNamespace, connection: Connection? = null): Int =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(deleteAllInNamespaceSql).use { stmt ->
                stmt.setString(1, namespace.toString())
                stmt.executeUpdate()
            }
        }

    private val deleteAllInNamespaceSql by lazy { "DELETE FROM $tableName WHERE $NAMESPACE_COLUMN = ?" }

    /**
     * Counts records for the given namespace.
     */
    suspend fun countAllInNamespace(namespace: WorkflowNamespace, connection: Connection? = null): Long =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(countAllInNamespaceSql).use { stmt ->
                stmt.setString(1, namespace.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    private val countAllInNamespaceSql by lazy { "SELECT COUNT(*) FROM $tableName WHERE $NAMESPACE_COLUMN = ?" }

    /**
     * Finds all versions of a workflow by namespace and name.
     */
    suspend fun listByName(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        connection: Connection? = null
    ): List<DefinitionModel> = databaseConfig.withConnection(connection) {
        it.prepareStatement(listByNameSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val listByNameSql by lazy { "SELECT * FROM $tableName WHERE $NAMESPACE_COLUMN = ? AND $NAME_COLUMN = ?" }

    /**
     * Finds a workflow by name and version.
     */
    suspend fun findByNameAndVersion(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion,
        connection: Connection? = null
    ): DefinitionModel? = databaseConfig.withConnection(connection) {
        it.prepareStatement(findByNameAndVersionSql).use { stmt ->
            stmt.setString(1, namespace.toString())
            stmt.setString(2, name.toString())
            stmt.setString(3, version.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByNameAndVersionSql by lazy {
        "SELECT * FROM $tableName WHERE $NAMESPACE_COLUMN = ? AND $NAME_COLUMN = ? AND $VERSION_COLUMN = ? LIMIT 1"
    }
}
