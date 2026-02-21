// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.PgmqAnalyticsTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class PgmqAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            DATABASE_TYPE to DatabaseType.POSTGRESQL.configValue,
            MESSAGING_TYPE to MessagingType.PGMQ.configValue,
            COMMANDS_CONSUMER_ENABLED to "false",
            COMMANDS_PRODUCER_ENABLED to "false",
            EVENTS_CONSUMER_ENABLED to "false",
            EVENTS_PRODUCER_ENABLED to "false",
            CLOUDEVENTS_CONSUMER_ENABLED to "false",
            CLOUDEVENTS_PRODUCER_ENABLED to "false",
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "16",
            "lemline.messaging.pgmq.lifecycleevents.queue" to "lemline-lifecycle-analytics-pgmq",
            "quarkus.arc.exclude-types" to
                "com.lemline.runner.testcases.lifecycleevents.TestLifecycleEventListener," +
                "com.lemline.runner.testcases.inMemory.InMemoryWorkflowTestExecutor," +
                "com.lemline.runner.testcases.bases.BrokerWorkflowTestExecutor",
            "lemline.analytics.migrate-at-start" to "true",
            "lemline.analytics.baseline-on-migrate" to "false",
            "lemline.outbox.enabled" to "false",
            "lemline.scheduled.enabled" to "false"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(PgmqAnalyticsTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("analytics", MessagingType.PGMQ.configValue)
    }
}
