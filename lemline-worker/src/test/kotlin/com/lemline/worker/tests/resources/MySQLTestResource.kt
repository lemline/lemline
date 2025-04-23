// SPDX-License-Identifier: BUSL-1.1
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

        // Return the profile setting
        return mapOf(
            "lemline.database.mysql.host" to mysql.host,
            "lemline.database.mysql.port" to mysql.firstMappedPort.toString(),
            "lemline.database.mysql.name" to mysql.databaseName,
            "lemline.database.mysql.username" to mysql.username,
            "lemline.database.mysql.password" to mysql.password,
        )
    }

    override fun stop() {
        if (::mysql.isInitialized) {
            mysql.stop()
        }
    }
}
