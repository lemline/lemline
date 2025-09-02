// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.repositories.bases.FailureRepositoryTest
import com.lemline.runner.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the FailureRepositoryTest suite against a PostgresSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledOnlyIfDockerAvailable
@kotlin.time.ExperimentalTime
internal class PostgresFailureRepositoryTest : FailureRepositoryTest()
