// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.sqlite

import com.lemline.runner.common.config.*
import com.lemline.runner.testcases.bases.ScheduleExecutionProfile
import com.lemline.runner.tests.profiles.AnalyticsH2TestProfileSupport
import com.lemline.runner.tests.profiles.sqlitePostgresResources
import com.lemline.runner.tests.profiles.sqliteWorkflowChannels
import io.quarkus.test.junit.QuarkusTestProfile

class SqliteTestCaseProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = sqliteWorkflowChannels +
        AnalyticsH2TestProfileSupport.overrides("lemline_analytics_sqlite_testcase") + mapOf(
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
            LEMLINE_ORCHESTRATOR_MODE to "all",
            LEMLINE_NAMESPACES to "default,examples,samples,test",
            LEMLINE_OUTBOX_ENABLED to "true",
            LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER to "0s",
        )

    override fun testResources() = sqlitePostgresResources()
}

class SqliteScheduleExecutionProfile : ScheduleExecutionProfile(SqliteTestCaseProfile())
