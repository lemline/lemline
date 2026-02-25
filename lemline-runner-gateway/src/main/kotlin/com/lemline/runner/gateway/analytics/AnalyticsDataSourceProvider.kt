// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.runner.common.config.ANALYTICS_TYPE_DEFAULT
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class AnalyticsDataSourceProvider(
    @param:ConfigProperty(name = LEMLINE_ANALYTICS_TYPE, defaultValue = ANALYTICS_TYPE_DEFAULT)
    private val rawAnalyticsType: String,
) {
    private val analyticsType: AnalyticsType by lazy { AnalyticsType.fromConfigValue(rawAnalyticsType) }

    @Inject
    @DataSource("analytics")
    lateinit var h2DataSource: Instance<AgroalDataSource>

    @Inject
    @DataSource("analytics-postgresql")
    lateinit var postgresqlDataSource: Instance<AgroalDataSource>

    fun require(): AgroalDataSource = when (analyticsType) {
        AnalyticsType.H2 -> requireResolvable("H2", h2DataSource)
        AnalyticsType.POSTGRESQL -> requireResolvable("PostgreSQL", postgresqlDataSource)
    }

    fun optional(): AgroalDataSource? = runCatching { require() }.getOrNull()

    private fun requireResolvable(
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
}
