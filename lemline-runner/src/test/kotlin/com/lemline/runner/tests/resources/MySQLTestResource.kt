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
            "lemline.database.mysql.host" to mysql.host,
            "lemline.database.mysql.port" to mysql.firstMappedPort.toString(),
            "lemline.database.mysql.name" to mysql.databaseName,
            "lemline.database.mysql.username" to mysql.username,
            "lemline.database.mysql.password" to mysql.password,
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty("lemline.database.mysql.host")
        System.clearProperty("lemline.database.mysql.port")
        System.clearProperty("lemline.database.mysql.name")
        System.clearProperty("lemline.database.mysql.username")
        System.clearProperty("lemline.database.mysql.password")

        if (::mysql.isInitialized) {
            mysql.stop()
        }
    }
}
