package com.lemline.worker.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for Postgres tests.
 * This ensures that Postgres-specific configuration is used.
 */
class PostgresTestProfile : QuarkusTestProfile {
    override fun getConfigProfile() = "postgresql"
} 