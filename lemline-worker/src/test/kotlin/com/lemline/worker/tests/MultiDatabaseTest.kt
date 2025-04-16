package com.lemline.worker.tests

import com.lemline.worker.tests.resources.MySQLTestResource
import com.lemline.worker.tests.resources.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Tag

/**
 * Base class for tests that should run against PostgreSQL.
 * Instead of implementing this interface, use the annotations directly:
 *
 * ```
 * @QuarkusTest
 * @QuarkusTestResource(PostgresTestResource::class)
 * @TestInstance(TestInstance.Lifecycle.PER_CLASS)
 * @Tag("integration")
 * @Tag("postgresql")
 * ```
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("postgresql")
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PostgresTest

/**
 * Base class for tests that should run against MySQL.
 * Instead of implementing this interface, use the annotations directly:
 *
 * ```
 * @QuarkusTest
 * @QuarkusTestResource(MySQLTestResource::class)
 * @TestInstance(TestInstance.Lifecycle.PER_CLASS)
 * @Tag("integration")
 * @Tag("mysql")
 * ```
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("mysql")
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MySQLTest 