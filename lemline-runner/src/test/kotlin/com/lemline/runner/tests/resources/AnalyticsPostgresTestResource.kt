// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class AnalyticsPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<*>

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:14-alpine"))
            .withDatabaseName("lemline_analytics_test")
            .withUsername("test")
            .withPassword("test")

        postgres.start()

        val properties = mapOf(
            "lemline.analytics.postgresql.host" to postgres.host,
            "lemline.analytics.postgresql.port" to postgres.firstMappedPort.toString(),
            "lemline.analytics.postgresql.database" to postgres.databaseName,
            "lemline.analytics.postgresql.username" to postgres.username,
            "lemline.analytics.postgresql.password" to postgres.password,
            "lemline.analytics.postgresql.schema" to "public",
            "lemline.analytics.postgresql.table" to "lemline_lifecycle_events"
        )

        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        System.clearProperty("lemline.analytics.postgresql.host")
        System.clearProperty("lemline.analytics.postgresql.port")
        System.clearProperty("lemline.analytics.postgresql.database")
        System.clearProperty("lemline.analytics.postgresql.username")
        System.clearProperty("lemline.analytics.postgresql.password")
        System.clearProperty("lemline.analytics.postgresql.schema")
        System.clearProperty("lemline.analytics.postgresql.table")

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
