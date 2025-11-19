// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.postgres

import com.lemline.runner.repositories.bases.ForkWaitingRepositoryTest
import com.lemline.runner.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.TestInstance

/**
 * Runs the ForkWaitingRepositoryTest suite against a PostgreSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class PostgresForkRepositoryTest : ForkWaitingRepositoryTest()
