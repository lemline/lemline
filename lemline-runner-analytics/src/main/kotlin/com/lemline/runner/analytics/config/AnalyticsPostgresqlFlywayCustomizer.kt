// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics.config

import io.quarkus.flyway.FlywayConfigurationCustomizer
import io.quarkus.flyway.FlywayDataSource
import jakarta.inject.Singleton
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension

@Singleton
@FlywayDataSource("analytics-postgresql")
class AnalyticsPostgresqlFlywayCustomizer : FlywayConfigurationCustomizer {
    override fun customize(configuration: FluentConfiguration) {
        // Concurrent index migrations need a session-level lock outside the migration transaction.
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).setTransactionalLock(false)
    }
}
