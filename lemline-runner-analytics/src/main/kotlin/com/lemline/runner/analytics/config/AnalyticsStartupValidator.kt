// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.sql.SQLException
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
internal class AnalyticsStartupValidator(
    @param:ConfigProperty(name = LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, defaultValue = LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT)
    val lifecycleConsumerEnabled: Boolean,
) {
    private val logger = logger()

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        if (!lifecycleConsumerEnabled) return
        verifyAnalyticsDatabaseConnectivity()
    }

    private fun verifyAnalyticsDatabaseConnectivity() {
        try {
            analyticsManager.datasource.connection.use { conn ->
                conn.isValid(5)
            }
            logger.info { "Analytics database connectivity verified." }
        } catch (e: SQLException) {
            throw AnalyticStartupException(
                """
                |
                |═══════════════════════════════════════════════════════════════════════════════
                |  LEMLINE_ANALYTICS LEMLINE_DATABASE CONNECTION FAILED
                |═══════════════════════════════════════════════════════════════════════════════
                |
                |  Unable to connect to the selected analytics database.
                |
                |  Please verify:
                |    • The selected analytics datasource is reachable
                |    • For PostgreSQL, 'lemline.analytics.postgresql.*' settings are correct
                |    • The database user has appropriate permissions
                |
                |  Error: ${e.message}
                |
                |═══════════════════════════════════════════════════════════════════════════════
                """.trimMargin(),
                e
            )
        }
    }

    /**
     * Exception thrown when database startup validation fails.
     * The message is designed to be user-friendly and actionable.
     */
    class AnalyticStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}
