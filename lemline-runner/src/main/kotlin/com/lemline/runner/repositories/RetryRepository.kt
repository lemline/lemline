// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val RETRY_TABLE = "lemline_retries"

@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class RetryRepository : OutboxRepository<RetryModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var failureRepository: FailureRepository

    override val tableName = RETRY_TABLE

    companion object {
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    // add the error
    private val retryMapping: Map<String, (PreparedStatement, RetryModel, Int) -> Unit> = mapOf(
        ERROR_REASON_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
            stmt.setString(idx, entity.errorReason)
        }) + (
        ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
            stmt.setString(idx, entity.errorClass)
        }) + (
        ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
            stmt.setString(idx, entity.errorMessage)
        }) + (
        ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: RetryModel, idx: Int ->
            stmt.setString(idx, entity.errorStackTrace)
        })

    override val prepareStatementMap by lazy {
        super.prepareStatementMap + retryMapping
    }

    override fun createModel(rs: ResultSet) = RetryModel(
        id = rs.id,
        instanceMessage = rs.instanceMessage,
        runStatus = rs.outBoxStatus,
        runAt = rs.runAt,
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN)
    ).apply {
        runDelayedUntil = rs.runDelayedUntil
        runAttemptCount = rs.runAttemptCount
        runLastErrorClass = rs.runLastErrorClass
        runLastErrorMessage = rs.runLastErrorMessage
        runLastErrorStackTrace = rs.runLastErrorStackTrace
    }
}
