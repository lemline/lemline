package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Interface for database test resources that provides database connectivity information.
 */
interface DatabaseTestResource : QuarkusTestResourceLifecycleManager {
    /**
     * Returns the database type (postgresql or mysql).
     */
    fun getDatabaseType(): String

    /**
     * Returns the JDBC URL for this database.
     */
    fun getJdbcUrl(): String

    /**
     * Returns the username for this database.
     */
    fun getUsername(): String

    /**
     * Returns the password for this database.
     */
    fun getPassword(): String

    /**
     * Returns the database kind (postgresql or mysql).
     */
    fun getDbKind(): String
} 