// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.core.testcases.RunShellTestCases
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime

/**
 * Tests for Shell command execution using runner messaging infrastructure.
 *
 * Note: Tests are platform-specific (unix-only or windows-only tags).
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class RunShellExecutionTest : AbstractRunnerWorkflowTest(RunShellTestCases.cases)
