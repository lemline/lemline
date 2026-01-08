// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_POSITION_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_VERSION_COLUMN
import com.lemline.runner.listeners.ListenerRepository.Companion.CORRELATION_VALUES_COLUMN
import java.sql.PreparedStatement
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Key for batch querying listeners by workflow identity, correlation, and strategy.
 * Optionally includes filterIndex for ALL strategy event insertion.
 */
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
data class ListenerQueryKey(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val correlationValuesJson: String?,
    /** Filter index for ALL strategy - indicates which filter matched (null for ONE/ANY) */
    val filterIndex: Int? = null,
    /** Strategy for filtering listeners (null to match any strategy) */
    val listenerStrategy: ListenerStrategy? = null
) {
    /**
     * Builds SQL WHERE condition for this key.
     */
    fun toSqlCondition(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        val baseCondition =
            "$prefix$WORKFLOW_NAMESPACE_COLUMN = ? AND $prefix$WORKFLOW_NAME_COLUMN = ? AND $prefix$WORKFLOW_VERSION_COLUMN = ? AND $prefix${WORKFLOW_POSITION_COLUMN} = ?"

        val correlationCondition = if (correlationValuesJson != null) {
            " AND ($prefix$CORRELATION_VALUES_COLUMN IS NULL OR $prefix$CORRELATION_VALUES_COLUMN = ?)"
        } else ""

        val strategyCondition = if (listenerStrategy != null) {
            " AND $prefix${ListenerRepository.STRATEGY_COLUMN} = ?"
        } else ""

        return "($baseCondition$correlationCondition$strategyCondition)"
    }

    /**
     * Binds this key's parameters to a PreparedStatement starting at the given index.
     */
    fun bindParameters(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        if (correlationValuesJson != null) stmt.setString(idx++, correlationValuesJson)
        if (listenerStrategy != null) stmt.setString(idx++, listenerStrategy.name)

        return idx
    }

    fun toSqlConditionWithoutCorrelation(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return "($prefix$WORKFLOW_NAMESPACE_COLUMN = ? AND $prefix$WORKFLOW_NAME_COLUMN = ? AND $prefix$WORKFLOW_VERSION_COLUMN = ? AND $prefix${WORKFLOW_POSITION_COLUMN} = ?)"
    }

    fun bindParametersWithoutCorrelation(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        return idx
    }

    companion object {
        fun buildWhereClause(keys: List<ListenerQueryKey>, tableAlias: String = ""): String =
            keys.joinToString(" OR ") { it.toSqlCondition(tableAlias) }

        fun bindAllParameters(keys: List<ListenerQueryKey>, stmt: PreparedStatement, startIndex: Int): Int {
            var idx = startIndex
            for (key in keys) {
                idx = key.bindParameters(stmt, idx)
            }
            return idx
        }

        fun buildWhereClauseWithoutCorrelation(keys: List<ListenerQueryKey>, tableAlias: String = ""): String =
            keys.joinToString(" OR ") { it.toSqlConditionWithoutCorrelation(tableAlias) }

        fun bindAllParametersWithoutCorrelation(
            keys: List<ListenerQueryKey>,
            stmt: PreparedStatement,
            startIndex: Int
        ): Int {
            var idx = startIndex
            for (key in keys) {
                idx = key.bindParametersWithoutCorrelation(stmt, idx)
            }
            return idx
        }
    }
}
