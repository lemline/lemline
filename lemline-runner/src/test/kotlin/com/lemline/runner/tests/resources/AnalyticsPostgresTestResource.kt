// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_SCHEMA
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME
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
            .withDatabaseName("lemline_test")
            .withUsername("test")
            .withPassword("test")

        postgres.start()

        val properties = mapOf(
            LEMLINE_ANALYTICS_POSTGRES_HOST to postgres.host,
            LEMLINE_ANALYTICS_POSTGRES_PORT to postgres.firstMappedPort.toString(),
            LEMLINE_ANALYTICS_POSTGRES_DATABASE to postgres.databaseName,
            LEMLINE_ANALYTICS_POSTGRES_USERNAME to postgres.username,
            LEMLINE_ANALYTICS_POSTGRES_PASSWORD to postgres.password,
            LEMLINE_ANALYTICS_POSTGRES_SCHEMA to "public",
        )

        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_HOST)
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_PORT)
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_DATABASE)
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_USERNAME)
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_PASSWORD)
        System.clearProperty(LEMLINE_ANALYTICS_POSTGRES_SCHEMA)

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
