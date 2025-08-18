// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.repositories.bases.ScheduleRepositoryTest
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowRepositoryTest suite against an H2 database.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
internal class H2ScheduleRepositoryTest : ScheduleRepositoryTest()
