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
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST to postgres.host,
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT to postgres.firstMappedPort.toString(),
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE to postgres.databaseName,
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME to postgres.username,
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD to postgres.password,
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_SCHEMA to "public",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_TABLE to "lemline_lifecycle_events"
        )

        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_SCHEMA)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_TABLE)

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
