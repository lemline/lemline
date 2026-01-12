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
            "lemline.database.type" to "postgresql",
            "lemline.database.postgresql.host" to postgres.host,
            "lemline.database.postgresql.port" to postgres.firstMappedPort.toString(),
            "lemline.database.postgresql.name" to postgres.databaseName,
            "lemline.database.postgresql.username" to postgres.username,
            "lemline.database.postgresql.password" to postgres.password
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty("lemline.database.type")
        System.clearProperty("lemline.database.postgresql.host")
        System.clearProperty("lemline.database.postgresql.port")
        System.clearProperty("lemline.database.postgresql.name")
        System.clearProperty("lemline.database.postgresql.username")
        System.clearProperty("lemline.database.postgresql.password")

        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }
}
