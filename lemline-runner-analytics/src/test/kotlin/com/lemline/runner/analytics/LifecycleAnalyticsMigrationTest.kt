// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.analytics.config.AnalyticsPostgresqlFlywayCustomizer
import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleAnalyticsMigrationTest {
    @Test
    fun `H2 migrations retain the unfiltered lifecycle event index`() {
        H2TestDatabaseConfig("classpath:db/migration/analytics/h2").use { database ->
            database.migrate()
            database.jdbcDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = ?"
                ).use { statement ->
                    statement.setString(1, H2_INDEX_NAME.uppercase())
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }
    }

    @RequiresDocker
    @Test
    fun `PostgreSQL migration removes the obsolete partial lifecycle index`() {
        PostgresTestDatabaseConfig("classpath:db/migration/analytics/postgresql").use { database ->
            database.start()
            val configuration = Flyway.configure()
                .dataSource(database.jdbcDataSource)
                .locations("classpath:db/migration/analytics/postgresql")
                .defaultSchema("lemline")
                .createSchemas(true)
            AnalyticsPostgresqlFlywayCustomizer().customize(configuration)
            configuration.load().migrate()
            database.jdbcDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'lemline' AND indexname = ?"
                ).use { statement ->
                    statement.setString(1, H2_INDEX_NAME)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'lemline' AND indexname = ?"
                ).use { statement ->
                    statement.setString(1, POSTGRESQL_INDEX_NAME)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }
    }

    private companion object {
        const val H2_INDEX_NAME = "idx_lemline_lifecycle_events_namespace_wftype_time"
        const val POSTGRESQL_INDEX_NAME = "idx_lemline_lifecycle_events_namespace_type"
    }
}
