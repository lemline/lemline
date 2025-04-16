package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for PostgreSQL tests.
 * This ensures that PostgreSQL-specific configuration is used.
 */
class PostgresTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String {
        return "postgresql"
    }
} 