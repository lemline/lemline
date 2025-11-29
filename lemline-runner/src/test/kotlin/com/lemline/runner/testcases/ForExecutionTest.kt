// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.core.testcases.ForTaskTestCases
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime

/**
 * Tests for For task execution using runner messaging infrastructure.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class ForExecutionTest : AbstractRunnerWorkflowTest(ForTaskTestCases.cases)
