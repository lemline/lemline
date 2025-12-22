// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_POSITION_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_VERSION_COLUMN
import java.sql.PreparedStatement
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Key for batch querying listeners by workflow identity and correlation.
 * Optionally includes filterIndex for ALL strategy event insertion.
 */
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
data class ListenerQueryKey(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val correlationValuesJson: String?,
    /** Filter index for ALL strategy - indicates which filter matched (null for ONE/ANY) */
    val filterIndex: Int? = null
) {
    /**
     * Builds SQL WHERE condition for this key.
     */
    fun toSqlCondition(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return if (correlationValuesJson == null) {
            "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ?)"
        } else {
            "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ? AND (${prefix}${ListenerRepository.CORRELATION_VALUES_COLUMN} IS NULL OR ${prefix}${ListenerRepository.CORRELATION_VALUES_COLUMN} = ?))"
        }
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
        if (correlationValuesJson != null) {
            stmt.setString(idx++, correlationValuesJson)
        }
        return idx
    }

    /**
     * Builds SQL WHERE condition without correlation check (used for termination events).
     */
    fun toSqlConditionWithoutCorrelation(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return "(${prefix}${WORKFLOW_NAMESPACE_COLUMN} = ? AND ${prefix}${WORKFLOW_NAME_COLUMN} = ? AND ${prefix}${WORKFLOW_VERSION_COLUMN} = ? AND ${prefix}${WORKFLOW_POSITION_COLUMN} = ?)"
    }

    /**
     * Binds parameters without correlation value (used for termination events).
     */
    fun bindParametersWithoutCorrelation(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        return idx
    }

    companion object {
        /**
         * Builds combined WHERE clause for multiple keys using OR.
         */
        fun buildWhereClause(keys: List<ListenerQueryKey>, tableAlias: String = ""): String =
            keys.joinToString(" OR ") { it.toSqlCondition(tableAlias) }

        /**
         * Binds parameters for all keys to a PreparedStatement.
         */
        fun bindAllParameters(keys: List<ListenerQueryKey>, stmt: PreparedStatement, startIndex: Int): Int {
            var idx = startIndex
            for (key in keys) {
                idx = key.bindParameters(stmt, idx)
            }
            return idx
        }
    }
}
