package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for MySQL tests.
 * This ensures that MySQL-specific configuration is used.
 */
class MySQLTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String {
        return "mysql"
    }
} 