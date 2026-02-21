// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.start

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

@ApplicationScoped
class GatewayWorkflowReservationRepository {

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    suspend fun reserve(
        workflowId: WorkflowId,
        workflowNamespace: String,
        workflowName: String,
        workflowVersion: String,
        inputJson: String,
        zoneId: String,
        requestFingerprint: String,
    ): ReserveResult = databaseConfig.withTransaction { conn ->
        val existing = findByWorkflowId(workflowId, conn)
        if (existing != null) {
            val resultType = if (existing.requestFingerprint == requestFingerprint) {
                ReserveResultType.EXISTING_SAME
            } else {
                ReserveResultType.EXISTING_MISMATCH
            }
            return@withTransaction ReserveResult(resultType, existing)
        }

        val startedAt = Clock.System.now()
        val inserted = conn.prepareStatement(insertSql).use { stmt ->
            setWorkflowId(stmt, 1, workflowId)
            stmt.setString(2, workflowNamespace)
            stmt.setString(3, workflowName)
            stmt.setString(4, workflowVersion)
            stmt.setString(5, inputJson)
            stmt.setString(6, zoneId)
            stmt.setString(7, requestFingerprint)
            stmt.setObject(8, OffsetDateTime.ofInstant(startedAt.toJavaInstant(), ZoneOffset.UTC))
            stmt.executeUpdate()
        }

        if (inserted == 1) {
            val reservation = GatewayWorkflowReservation(
                workflowId = workflowId,
                workflowNamespace = workflowNamespace,
                workflowName = workflowName,
                workflowVersion = workflowVersion,
                inputJson = inputJson,
                zoneId = zoneId,
                requestFingerprint = requestFingerprint,
                startedAt = startedAt
            )
            return@withTransaction ReserveResult(ReserveResultType.NEW, reservation)
        }

        val current = findByWorkflowId(workflowId, conn)
            ?: error("Workflow reservation vanished after insert conflict for workflow_id=$workflowId")

        val resultType = if (current.requestFingerprint == requestFingerprint) {
            ReserveResultType.EXISTING_SAME
        } else {
            ReserveResultType.EXISTING_MISMATCH
        }
        ReserveResult(resultType, current)
    }

    suspend fun findByWorkflowId(workflowId: WorkflowId): GatewayWorkflowReservation? =
        databaseConfig.withConnection { conn ->
            findByWorkflowId(workflowId, conn)
        }

    suspend fun deleteByWorkflowId(workflowId: WorkflowId): Int = databaseConfig.withConnection { conn ->
        conn.prepareStatement(deleteSql).use { stmt ->
            setWorkflowId(stmt, 1, workflowId)
            stmt.executeUpdate()
        }
    }

    private fun findByWorkflowId(
        workflowId: WorkflowId,
        conn: java.sql.Connection
    ): GatewayWorkflowReservation? = conn.prepareStatement(selectSql).use { stmt ->
        setWorkflowId(stmt, 1, workflowId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) rs.toReservation() else null
        }
    }

    private fun ResultSet.toReservation(): GatewayWorkflowReservation {
        val workflowId = WorkflowId(getWorkflowId(this, "workflow_id"))
        val startedAt = getTimestamp("started_at")?.toInstant()?.toKotlinInstant() ?: Clock.System.now()

        return GatewayWorkflowReservation(
            workflowId = workflowId,
            workflowNamespace = getString("workflow_namespace"),
            workflowName = getString("workflow_name"),
            workflowVersion = getString("workflow_version"),
            inputJson = getString("input_json"),
            zoneId = getString("zone_id"),
            requestFingerprint = getString("request_fingerprint"),
            startedAt = startedAt
        )
    }

    private fun setWorkflowId(stmt: PreparedStatement, index: Int, workflowId: WorkflowId) {
        when (databaseConfig.dbType) {
            DatabaseType.POSTGRESQL, DatabaseType.H2 -> stmt.setObject(index, workflowId.value.value)
            DatabaseType.MYSQL -> stmt.setBytes(index, workflowId.value.toBytes())
        }
    }

    private fun getWorkflowId(rs: ResultSet, column: String): IDV7 = when (databaseConfig.dbType) {
        DatabaseType.POSTGRESQL, DatabaseType.H2 -> IDV7(rs.getObject(column, java.util.UUID::class.java))
        DatabaseType.MYSQL -> {
            val bytes = rs.getBytes(column)
                ?: throw IllegalStateException("Missing binary workflow_id in column '$column'")
            IDV7.from(bytes)
        }
    }

    private val insertSql: String by lazy {
        val values = "(?, ?, ?, ?, ?, ?, ?, ?, NULL)"
        when (databaseConfig.dbType) {
            DatabaseType.MYSQL -> """
                INSERT IGNORE INTO lemline_gateway_workflows (
                    workflow_id,
                    workflow_namespace,
                    workflow_name,
                    workflow_version,
                    input_json,
                    zone_id,
                    request_fingerprint,
                    started_at,
                    updated_at
                )
                VALUES $values
            """.trimIndent()

            DatabaseType.POSTGRESQL, DatabaseType.H2 -> """
                INSERT INTO lemline_gateway_workflows (
                    workflow_id,
                    workflow_namespace,
                    workflow_name,
                    workflow_version,
                    input_json,
                    zone_id,
                    request_fingerprint,
                    started_at,
                    updated_at
                )
                VALUES $values
                ON CONFLICT DO NOTHING
            """.trimIndent()
        }
    }

    private val selectSql: String by lazy {
        "SELECT * FROM lemline_gateway_workflows WHERE workflow_id = ? LIMIT 1"
    }

    private val deleteSql: String by lazy {
        "DELETE FROM lemline_gateway_workflows WHERE workflow_id = ?"
    }
}
