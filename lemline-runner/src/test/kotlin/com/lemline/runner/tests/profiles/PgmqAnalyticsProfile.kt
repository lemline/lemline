// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED
import com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.PgmqAnalyticsTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class PgmqAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            LEMLINE_DATABASE_TYPE to DatabaseType.POSTGRESQL.configValue,
            LEMLINE_MESSAGING_TYPE to MessagingType.PGMQ.configValue,
            LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "16",
            LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-analytics-pgmq",
            "quarkus.arc.exclude-types" to
                "com.lemline.runner.testcases.bases.InMemoryWorkflowTestExecutor," +
                "com.lemline.runner.testcases.bases.BrokerWorkflowTestExecutor",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
            LEMLINE_OUTBOX_ENABLED to "false",
            LEMLINE_SCHEDULED_ENABLED to "false"
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_pgmq")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(PgmqAnalyticsTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("analytics", MessagingType.PGMQ.configValue)
    }
}
