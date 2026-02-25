// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

import com.lemline.common.debug
import com.lemline.common.logger
import com.lemline.common.trace
import com.lemline.runner.common.config.ANALYTICS_TYPE_DEFAULT
import com.lemline.runner.common.config.AnalyticsConfig
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import io.quarkus.flyway.FlywayDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.flywaydb.core.Flyway

@ApplicationScoped
internal class AnalyticsManager(
    @param:ConfigProperty(name = LEMLINE_ANALYTICS_TYPE, defaultValue = ANALYTICS_TYPE_DEFAULT)
    private val rawAnalyticsType: String,
) : AnalyticsConfig {
    private val log = logger()

    override val analyticsType: AnalyticsType by lazy {
        AnalyticsType.fromConfigValue(rawAnalyticsType)
    }

    @Inject
    @DataSource("analytics")
    internal lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("analytics-postgresql")
    internal lateinit var postgresqlDataSource: Instance<AgroalDataSource>

    @Inject
    @FlywayDataSource("analytics")
    internal lateinit var h2Flyway: Instance<Flyway>

    @Inject
    @FlywayDataSource("analytics-postgresql")
    internal lateinit var postgresqlFlyway: Instance<Flyway>

    val datasource: AgroalDataSource by lazy {
        log.debug { "Resolving analytics datasource for type: ${analyticsType.configValue}" }
        log.trace { "- Analytics H2 datasource resolvable: ${h2DataSource.isResolvable}" }
        log.trace { "- Analytics PostgreSQL datasource resolvable: ${postgresqlDataSource.isResolvable}" }

        when (analyticsType) {
            AnalyticsType.H2 -> requireResolvableDataSource("H2", h2DataSource)
            AnalyticsType.POSTGRESQL -> requireResolvableDataSource("PostgreSQL", postgresqlDataSource)
        }
    }

    val flyway: Flyway by lazy {
        log.debug { "Resolving analytics flyway for type: ${analyticsType.configValue}" }
        log.trace { "- Analytics H2 flyway resolvable: ${h2Flyway.isResolvable}" }
        log.trace { "- Analytics PostgreSQL flyway resolvable: ${postgresqlFlyway.isResolvable}" }

        when (analyticsType) {
            AnalyticsType.H2 -> requireResolvableFlyway("H2", h2Flyway)
            AnalyticsType.POSTGRESQL -> requireResolvableFlyway("PostgreSQL", postgresqlFlyway)
        }
    }

    private fun requireResolvableDataSource(
        datasourceLabel: String,
        datasource: Instance<AgroalDataSource>
    ): AgroalDataSource {
        if (datasource.isResolvable) return datasource.get()
        throw IllegalStateException(
            "$datasourceLabel analytics datasource is not available. " +
                "Enable '$LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED'. " +
                "For PostgreSQL, configure '$LEMLINE_ANALYTICS_TYPE=postgresql' and $LEMLINE_ANALYTICS_POSTGRES.*"
        )
    }

    private fun requireResolvableFlyway(
        datasourceLabel: String,
        flyway: Instance<Flyway>
    ): Flyway {
        if (flyway.isResolvable) return flyway.get()
        throw IllegalStateException(
            "$datasourceLabel analytics flyway is not available. " +
                "Enable '$LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED'. " +
                "For PostgreSQL, configure '$LEMLINE_ANALYTICS_TYPE=postgresql' and $LEMLINE_ANALYTICS_POSTGRES.*"
        )
    }

    override suspend fun <R> withConnection(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> datasource.connection.use { block(it) }
            else -> block(connection)
        }
    }

    override suspend fun <R> withTransaction(
        connection: Connection?,
        block: suspend (Connection) -> R
    ): R = withContext(Dispatchers.IO) {
        when (connection) {
            null -> {
                datasource.connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        block(conn).also { conn.commit() }
                    } catch (t: Throwable) {
                        conn.rollback()
                        throw t
                    }
                }
            }

            else -> block(connection)
        }
    }
}
