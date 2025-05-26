// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.repositories.bases.WorkflowRepositoryTest
import com.lemline.runner.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowRepositoryTest suite against a PostgresSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledOnlyIfDockerAvailable
internal class PostgresWorkflowRepositoryTest : WorkflowRepositoryTest()
