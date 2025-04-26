// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.repositories.bases.WorkflowRepositoryTest
import com.lemline.worker.tests.profiles.PostgresProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowRepositoryTest suite against a PostgresSQL database.
 */
@QuarkusTest
@TestProfile(PostgresProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PostgresWorkflowRepositoryTest : WorkflowRepositoryTest()
