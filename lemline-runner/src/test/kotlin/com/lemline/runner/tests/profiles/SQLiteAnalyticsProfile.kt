// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.ANALYTICS_TYPE_SQLITE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_SQLITE_PATH
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_WATCH_POLL_INTERVAL_MS
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import java.nio.file.Files
import java.nio.file.Path

class SQLiteAnalyticsProfile : InMemoryAnalyticsProfile() {
    override val analyticsOverrides = mapOf(
        LEMLINE_ANALYTICS_TYPE to ANALYTICS_TYPE_SQLITE,
        LEMLINE_GATEWAY_WATCH_POLL_INTERVAL_MS to "20",
    )

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(QuarkusTestProfile.TestResourceEntry(SQLiteAnalyticsFile::class.java))
}

class SQLiteAnalyticsFile : QuarkusTestResourceLifecycleManager {
    private lateinit var directory: Path

    override fun start(): Map<String, String> {
        directory = Files.createTempDirectory("lemline-analytics-test-")
        return mapOf(LEMLINE_ANALYTICS_SQLITE_PATH to directory.resolve("analytics.db").toString())
    }

    override fun stop() {
        if (::directory.isInitialized) directory.toFile().deleteRecursively()
    }
}
