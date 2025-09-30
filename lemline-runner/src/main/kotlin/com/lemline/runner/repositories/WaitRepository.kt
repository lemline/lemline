// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.OutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val WAIT_TABLE = "lemline_waits"


@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class WaitRepository : OutboxRepository<WaitModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var failureRepository: FailureRepository

    override val tableName = WAIT_TABLE

    override fun createModel(rs: ResultSet) = WaitModel(
        id = rs.id,
        instanceMessage = rs.instanceMessage,
        runStatus = rs.outBoxStatus,
        runAt = rs.runAt,
    ).apply {
        runDelayedUntil = rs.runDelayedUntil
        runAttemptCount = rs.runAttemptCount
        runLastErrorClass = rs.runLastErrorClass
        runLastErrorMessage = rs.runLastErrorMessage
        runLastErrorStackTrace = rs.runLastErrorStackTrace
    }
}
