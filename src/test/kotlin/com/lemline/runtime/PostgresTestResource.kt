package com.lemline.runtime

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("swruntime_test")
            .withUsername("test")
            .withPassword("test")

        postgres?.start()

        return mapOf(
            "quarkus.datasource.jdbc.url" to (postgres?.jdbcUrl ?: ""),
            "quarkus.datasource.username" to (postgres?.username ?: ""),
            "quarkus.datasource.password" to (postgres?.password ?: "")
        )
    }

    override fun stop() {
        postgres?.stop()
    }
} 