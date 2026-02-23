// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

class MySQLTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var mysql: MySQLContainer<*>

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        mysql = MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("swruntime_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--default-authentication-plugin=mysql_native_password")

        mysql.start()

        // Return the profile setting
        val properties = mapOf(
            com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_HOST to mysql.host,
            com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PORT to mysql.firstMappedPort.toString(),
            com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_DATABASE to mysql.databaseName,
            com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_USERNAME to mysql.username,
            com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PASSWORD to mysql.password,
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_HOST)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PORT)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_DATABASE)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_USERNAME)
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_DATABASE_MYSQL_PASSWORD)

        if (::mysql.isInitialized) {
            mysql.stop()
        }
    }
}
