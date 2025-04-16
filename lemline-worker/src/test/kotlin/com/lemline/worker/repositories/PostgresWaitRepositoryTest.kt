package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.AbstractWaitRepositoryTest
import com.lemline.worker.tests.profiles.PostgresTestProfile
import com.lemline.worker.tests.resources.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * PostgreSQL-specific implementation of WaitRepositoryTest.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PostgresTestProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("postgresql")
class PostgresWaitRepositoryTest : AbstractWaitRepositoryTest()