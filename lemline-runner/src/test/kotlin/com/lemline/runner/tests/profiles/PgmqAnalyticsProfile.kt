// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.PgmqAnalyticsTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class PgmqAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            "lemline.database.type" to DatabaseType.POSTGRESQL.configValue,
            "lemline.messaging.type" to MessagingType.PGMQ.configValue,
            "lemline.messaging.commands.consumer.enabled" to "false",
            "lemline.messaging.commands.producer.enabled" to "false",
            "lemline.messaging.events.consumer.enabled" to "false",
            "lemline.messaging.events.producer.enabled" to "false",
            "lemline.messaging.cloudevents.consumer.enabled" to "false",
            "lemline.messaging.cloudevents.producer.enabled" to "false",
            "lemline.messaging.lifecycleevents.producer.enabled" to "true",
            "lemline.analytics.consumer.enabled" to "true",
            "lemline.analytics.consumer.concurrency" to "16",
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
