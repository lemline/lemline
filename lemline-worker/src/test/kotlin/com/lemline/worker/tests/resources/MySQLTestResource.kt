package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Test resource for MySQL database.
 * This spins up a MySQL container for tests.
 */
class MySQLTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var mysql: MySQLContainer<*>

    override fun start(): Map<String, String> {
        mysql = MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("swruntime_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--default-authentication-plugin=mysql_native_password")

        mysql.start()

        // Set system properties for datasource config - Quarkus will pick these up
        System.setProperty("quarkus.datasource.db-kind", "mysql")
        System.setProperty("quarkus.datasource.jdbc.url", mysql.jdbcUrl)
        System.setProperty("quarkus.datasource.username", mysql.username)
        System.setProperty("quarkus.datasource.password", mysql.password)
        // Do NOT set flyway locations dynamically

        // Only return the profile setting
        return mapOf(
            "quarkus.profile" to "mysql",
            "lemline.database.type" to "mysql"
        )
    }

    override fun stop() {
        if (::mysql.isInitialized) {
            mysql.stop()
            // Clean up system properties
            System.clearProperty("quarkus.datasource.db-kind")
            System.clearProperty("quarkus.datasource.jdbc.url")
            System.clearProperty("quarkus.datasource.username")
            System.clearProperty("quarkus.datasource.password")
        }
    }
} 