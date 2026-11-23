// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_SQLITE_PATH
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
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
    @param:ConfigProperty(name = LEMLINE_GATEWAY_ENABLED, defaultValue = "false")
    val gatewayEnabled: Boolean,
) {
    private val logger = logger()

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        if (!lifecycleConsumerEnabled && !(gatewayEnabled && analyticsManager.analyticsType == AnalyticsType.SQLITE)) return
        verifyAnalyticsDatabaseConnectivity()
    }

    private fun verifyAnalyticsDatabaseConnectivity() {
        try {
            analyticsManager.datasource.connection.use { conn ->
                if (analyticsManager.analyticsType == AnalyticsType.SQLITE) {
                    conn.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA journal_mode=WAL").use { rows ->
                            if (!rows.next() || !rows.getString(1).equals("wal", ignoreCase = true)) {
                                throw SQLException("Could not enable WAL for the SQLite analytics database")
                            }
                        }
                    }
                } else {
                    conn.isValid(5)
                }
            }
            logger.info { "Analytics database connectivity verified." }
        } catch (e: SQLException) {
            val checks = if (analyticsManager.analyticsType == AnalyticsType.SQLITE) {
                """
                |    • '$LEMLINE_ANALYTICS_SQLITE_PATH' names a file on a local disk
                |    • The process can write to that file and to its parent directory
                """.trimMargin()
            } else {
                """
                |    • The selected analytics datasource is reachable
                |    • For PostgreSQL, 'lemline.analytics.postgresql.*' settings are correct
                |    • The database user has appropriate permissions
                """.trimMargin()
            }
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
                |$checks
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
