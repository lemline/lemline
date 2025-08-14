// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.SCHEDULE_TABLE
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.OutBoxStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import java.time.Duration

/**
 * Repository for managing wait messages in the outbox pattern.
 * This repository handles the persistence and retrieval of wait messages,
 * which are used to implement delayed execution in workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see OutboxRepository for base functionality and documentation
 * @see WaitModel for the message model
 * @see OutboxProcessor for the processing logic
 */
@ApplicationScoped
internal class ScheduleRepository : OutboxRepository<ScheduleModel>() {
    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = SCHEDULE_TABLE

    override fun createModel(rs: ResultSet) = ScheduleModel(
        workflowId = rs.getString("id"),
        cron = rs.getString("cron")?.let { cronParser.parse(it) },
        after = rs.getString("after")?.let { Duration.parse(it) },
        every = rs.getString("every")?.let { Duration.parse(it) },
        message = rs.getString("message"),
        status = OutBoxStatus.valueOf(rs.getString("status")),
        scheduledFor = rs.getInstant("scheduled_for"),
        delayedUntil = rs.getInstant("delayed_until"),
        attemptCount = rs.getInt("attempt_count"),
        lastError = rs.getString("last_error"),
    )


    companion object {
        private val cronParser by lazy { CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)) }
    }

}
