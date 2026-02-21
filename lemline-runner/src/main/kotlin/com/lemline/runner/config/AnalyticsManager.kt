// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import io.quarkus.flyway.FlywayDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.flywaydb.core.Flyway

@ApplicationScoped
internal class AnalyticsManager {

    @Inject
    @DataSource("analytics")
    internal lateinit var analyticsDataSource: Instance<AgroalDataSource>

    @Inject
    @FlywayDataSource("analytics")
    internal lateinit var analyticsFlyway: Instance<Flyway>

    val datasource: AgroalDataSource
        get() = if (analyticsDataSource.isResolvable) {
            analyticsDataSource.get()
        } else {
            throw IllegalStateException(
                "Analytics datasource 'analytics' is not available. " +
                    "Enable '$LIFECYCLE_EVENTS_CONSUMER_ENABLED' and configure lemline.analytics.postgresql.*"
            )
        }

    val flyway: Flyway
        get() = if (analyticsFlyway.isResolvable) {
            analyticsFlyway.get()
        } else {
            throw IllegalStateException(
                "Analytics Flyway datasource 'analytics' is not available. " +
                    "Enable '$LIFECYCLE_EVENTS_CONSUMER_ENABLED' and configure lemline.analytics.postgresql.*"
            )
        }
}
