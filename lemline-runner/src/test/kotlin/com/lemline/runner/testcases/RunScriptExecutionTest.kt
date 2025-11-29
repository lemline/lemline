// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime

/**
 * Tests for Script execution using runner messaging infrastructure.
 *
 * Note: Tests are platform-specific (unix-only tag) and require js/python runtimes.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class RunScriptExecutionTest : AbstractRunnerWorkflowTest(RunScriptTestCases.cases)
