// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_DATABASE_POSTGRES_USERNAME
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
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
            LEMLINE_DATABASE_TYPE to "postgresql",
            LEMLINE_DATABASE_POSTGRES_HOST to postgres.host,
            LEMLINE_DATABASE_POSTGRES_PORT to postgres.firstMappedPort.toString(),
            LEMLINE_DATABASE_POSTGRES_DATABASE to postgres.databaseName,
            LEMLINE_DATABASE_POSTGRES_USERNAME to postgres.username,
            LEMLINE_DATABASE_POSTGRES_PASSWORD to postgres.password
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty(LEMLINE_DATABASE_TYPE)
        System.clearProperty(LEMLINE_DATABASE_POSTGRES_HOST)
        System.clearProperty(LEMLINE_DATABASE_POSTGRES_PORT)
        System.clearProperty(LEMLINE_DATABASE_POSTGRES_DATABASE)
        System.clearProperty(LEMLINE_DATABASE_POSTGRES_USERNAME)
        System.clearProperty(LEMLINE_DATABASE_POSTGRES_PASSWORD)

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
