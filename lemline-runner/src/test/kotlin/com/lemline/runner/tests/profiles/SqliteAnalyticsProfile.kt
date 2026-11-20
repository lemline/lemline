// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.*
import com.lemline.runner.tests.resources.SqliteMessagingTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

class SqliteAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = AnalyticsH2TestProfileSupport.overrides("lemline_analytics_sqlite") + mapOf(
        LEMLINE_MESSAGING_TYPE to MessagingType.SQLITE.configValue,
        LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "false",
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "false",
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "false",
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "false",
        LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "false",
        LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "false",
        LEMLINE_OUTBOX_ENABLED to "false",
        LEMLINE_SCHEDULED_ENABLED to "false",
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
        LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
        LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
        "quarkus.arc.exclude-types" to
            "com.lemline.runner.testcases.bases.InMemoryWorkflowTestExecutor," +
            "com.lemline.runner.testcases.bases.BrokerWorkflowTestExecutor",
    )

    override fun testResources() = listOf(TestResourceEntry(SqliteMessagingTestResource::class.java))
}
