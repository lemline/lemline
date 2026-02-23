// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
internal class AnalyticsMigration(
    @param:ConfigProperty(name = LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, defaultValue = LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT)
    val lifecycleConsumerEnabled: Boolean,
    @param:ConfigProperty(
        name = "quarkus.flyway.analytics.migrate-at-start",
        defaultValue = ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
    )
    val migrateAtStart: Boolean,
) {
    private val logger = logger()

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    @Suppress("unused")
    fun onStart(@Observes @Priority(1) event: StartupEvent) {
        if (!lifecycleConsumerEnabled || !migrateAtStart) return

        analyticsManager.flyway.migrate()
        logger.info { "Flyway migrations applied successfully on analytics PostgreSQL database." }
    }
}
