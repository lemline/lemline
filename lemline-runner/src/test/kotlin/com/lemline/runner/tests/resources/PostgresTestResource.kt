// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTestResource : QuarkusTestResourceLifecycleManager {
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
            com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE to "postgresql",
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST to postgres.host,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT to postgres.firstMappedPort.toString(),
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE to postgres.databaseName,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME to postgres.username,
            com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD to postgres.password
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD)

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
