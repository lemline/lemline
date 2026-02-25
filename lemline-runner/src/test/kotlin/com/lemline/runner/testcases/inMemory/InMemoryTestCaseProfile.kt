// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.tests.profiles.InMemoryProfile
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * In-memory testcase profile that validates the full lifecycle-events chain:
 * producer -> lifecycleevents-in -> analytics subscriber -> analytics database.
 */
class InMemoryTestCaseProfile : QuarkusTestProfile {
    private val base = InMemoryProfile()

    override fun getConfigOverrides(): Map<String, String> {
        return base.configOverrides + mapOf(
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return base.testResources() + listOf(
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return base.tags() + setOf("in-memory-testcase")
    }
}
