// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime

/**
 * Tests for HTTP call execution using runner messaging infrastructure.
 *
 * Note: Tests tagged with "external" make real HTTP calls to external APIs.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class CallHttpExecutionTest : AbstractRunnerWorkflowTest(CallHttpTestCases.cases)
