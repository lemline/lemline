// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.OptionalOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val SCHEDULE_TABLE = "lemline_schedules"

@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ScheduleRepository : OptionalOutboxRepository<ScheduleModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var failureRepository: FailureRepository

    override val tableName = SCHEDULE_TABLE

    companion object {
        internal const val SCHEDULE_AFTER_COLUMN = "schedule_after"
        internal const val SCHEDULE_CRON_COLUMN = "schedule_cron"
        internal const val SCHEDULE_EVERY_COLUMN = "schedule_every"
        internal const val SCHEDULE_ZONE_COLUMN = "schedule_zone"
    }

    // add the after, cron and every column
    val scheduleMapping: Map<String, (PreparedStatement, ScheduleModel, Int) -> Unit> = mapOf(
        SCHEDULE_AFTER_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleAfter)
        },
        SCHEDULE_EVERY_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleEvery)
        },
        SCHEDULE_CRON_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleCron)
        },
        SCHEDULE_ZONE_COLUMN to { stmt: PreparedStatement, entity: ScheduleModel, idx: Int ->
            stmt.setString(idx, entity.scheduleZone)
        }
    )

    override val prepareStatementMap by lazy {
        super.prepareStatementMap + scheduleMapping
    }

    override fun createModel(rs: ResultSet) = ScheduleModel(
        id = rs.id,
        instanceMessage = rs.instanceMessage,
        runStatus = rs.outBoxStatus,
        runAt = rs.runAt,
        scheduleAfter = rs.getString(SCHEDULE_AFTER_COLUMN),
        scheduleEvery = rs.getString(SCHEDULE_EVERY_COLUMN),
        scheduleCron = rs.getString(SCHEDULE_CRON_COLUMN),
        scheduleZone = rs.getString(SCHEDULE_ZONE_COLUMN),
    ).apply {
        runDelayedUntil = rs.runDelayedUntil
        runAttemptCount = rs.runAttemptCount
        runLastErrorClass = rs.runLastErrorClass
        runLastErrorMessage = rs.runLastErrorMessage
        runLastErrorStackTrace = rs.runLastErrorStackTrace
    }
}

