// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class InMemoryAnalyticsProfile : QuarkusTestProfile {
    private val base = InMemoryProfile()

    override fun getConfigOverrides(): Map<String, String> {
        return base.configOverrides + mapOf(
            LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "16",
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "false",
            "lemline.analytics.migrate-at-start" to "true",
            "lemline.analytics.baseline-on-migrate" to "false",
            "lemline.outbox.enabled" to "false",
            "lemline.scheduled.enabled" to "false"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return base.testResources() + listOf(
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return base.tags() + setOf("analytics")
    }
}
