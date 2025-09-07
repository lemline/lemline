// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_MYSQL
import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_POSTGRESQL
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.models.WithId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.*
import kotlin.time.ExperimentalTime

/**
 * Base interface for outbox pattern repositories.
 * This interface defines the common operations for managing messages in the outbox pattern,
 * which is used to ensure reliable message delivery in distributed systems.
 *
 * Key features:
 * - Parallel processing safety using SKIP LOCKED
 * - Ordered processing based on timestamps
 * - Batch processing with configurable limits
 * - Automatic cleanup of processed messages
 *
 * Native SQL Queries:
 * This interface uses native SQL queries because Hibernate does not support the SKIP LOCKED feature.
 * While Hibernate provides other locking mechanisms, SKIP LOCKED is essential for our parallel processing
 * requirements as it allows multiple processors to work on different messages simultaneously without blocking.
 *
 * Database Support:
 * The SKIP LOCKED feature is supported by:
 * - PostgreSQL 9.5+
 * - Oracle 10g+
 * - MySQL 8.0+ (with InnoDB)
 * - MariaDB 10.3+ (with InnoDB)
 * - IBM DB2 9.7+
 *
 * Note: SQL Server uses a different syntax (UPDLOCK, READPAST) and is not supported
 *
 * Parallel Processing Safety:
 * The interface uses SKIP LOCKED in native SQL queries to ensure safe parallel processing:
 * 1. Multiple processors can run simultaneously without blocking each other
 * 2. Each processor gets a unique set of messages to process
 * 3. No message is processed by more than one processor at a time
 * 4. Failed locks are skipped, allowing other processors to continue
 * 5. Processing order is maintained within each batch
 *
 * @see OutboxModel for the base message model
 * @see com.lemline.runner.outbox.OutboxRelay for the processing logic
 */
@OptIn(ExperimentalTime::class)
abstract class WithIdRepository<T : WithId> : Repository<T>() {

    companion object {
        internal const val ID_COLUMN = "id"
    }

    protected val setIDV7 by lazy {
        when (databaseManager.dbType) {
            DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { stmt: PreparedStatement, parameterIndex: Int, id: IDV7? ->
                when (id) {
                    null -> stmt.setNull(parameterIndex, Types.OTHER)
                    else -> stmt.setObject(parameterIndex, id.value)
                }
            }

            DB_TYPE_MYSQL -> { stmt: PreparedStatement, parameterIndex: Int, id: IDV7? ->
                when (id) {
                    null -> stmt.setNull(parameterIndex, Types.BINARY)
                    else -> stmt.setBytes(parameterIndex, id.toBytes())
                }
            }

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    protected val getIDV7: (ResultSet, String) -> IDV7? by lazy {
        when (databaseManager.dbType) {
            DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL -> { rs: ResultSet, columnName: String ->
                rs.getObject(columnName, UUID::class.java)?.let { IDV7(it) }
            }

            DB_TYPE_MYSQL -> { rs: ResultSet, columnName: String ->
                rs.getBytes(columnName)?.let { IDV7.from(it) }
            }

            else -> error("Unsupported database type '${databaseManager.dbType}'")
        }
    }

    override val keyColumns: List<String> = listOf(ID_COLUMN)

    override val prepareStatementMap: Map<String, (PreparedStatement, T, Int) -> Unit> by lazy {
        mapOf(
            ID_COLUMN to { stmt: PreparedStatement, entity: T, idx: Int -> setIDV7(stmt, idx, entity.id) },
        )
    }

    /**
     * Finds an entity by its unique identifier.
     *
     * @param id The unique identifier of the entity to find.
     * @param connection An optional database connection to use. If null, a new connection is acquired.
     * @return The entity corresponding to the provided identifier, or null if no entity is found.
     */
    suspend fun findById(id: IDV7, connection: Connection? = null): T? = withConnection(connection) { conn ->
        conn.prepareStatement(findByIdSql).use { stmt ->
            setIDV7(stmt, 1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByIdSql by lazy { "SELECT * FROM $tableName WHERE $ID_COLUMN = ? LIMIT 1" }

    /**
     * Deletes an entity by its unique ID.
     *
     * @param id The unique identifier of the entity to delete.
     * @param connection An optional database connection to use. If null, a new connection is acquired.
     * @return The number of rows affected by the delete operation.
     */
    suspend fun deleteById(id: IDV7, connection: Connection? = null): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByIdSql).use { stmt ->
            setIDV7(stmt, 1, id)
            stmt.executeUpdate()
        }
    }

    private val deleteByIdSql: String by lazy { "DELETE FROM $tableName WHERE $ID_COLUMN = ?" }
}
