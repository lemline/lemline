// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class InMemoryAnalyticsProfile : QuarkusTestProfile {
    private val base = InMemoryProfile()

    override fun getConfigOverrides(): Map<String, String> {
        return base.configOverrides + mapOf(
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_CONCURRENCY to "16",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
            com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED to "false"
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
