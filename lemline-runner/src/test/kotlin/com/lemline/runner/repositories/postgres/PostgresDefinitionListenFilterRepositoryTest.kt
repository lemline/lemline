// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.postgres

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.repositories.bases.DefinitionListenFilterRepositoryTest
import com.lemline.runner.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.TestInstance

/**
 * Runs the DefinitionListenFilterRepositoryTest suite against a PostgreSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledOnlyIfDockerAvailable
@ExperimentalTime
@ExperimentalSerializationApi
internal class PostgresDefinitionListenFilterRepositoryTest : DefinitionListenFilterRepositoryTest()
