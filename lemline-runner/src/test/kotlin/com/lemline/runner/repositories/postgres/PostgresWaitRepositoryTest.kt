// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.postgres

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.repositories.bases.WaitRepositoryTest
import com.lemline.runner.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WaitRepositoryTest suite against a PostgresSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledOnlyIfDockerAvailable
@ExperimentalTime
@ExperimentalSerializationApi
internal class PostgresWaitRepositoryTest : WaitRepositoryTest()
