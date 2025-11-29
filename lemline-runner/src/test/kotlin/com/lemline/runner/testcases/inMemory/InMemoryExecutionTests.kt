// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.ExportContextTestCases
import com.lemline.core.testcases.ForTaskTestCases
import com.lemline.core.testcases.ForkTaskTestCases
import com.lemline.core.testcases.IfConditionTestCases
import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.core.testcases.RunShellTestCases
import com.lemline.core.testcases.RunWorkflowTestCases
import com.lemline.core.testcases.SetTaskTestCases
import com.lemline.core.testcases.SwitchTaskTestCases
import com.lemline.core.testcases.TryTaskTestCases
import com.lemline.core.testcases.WaitTestCases
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * In-memory workflow execution tests using runner messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * in-memory channels with manual routing between command/event handlers.
 */

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class CallHttpExecutionTest : InMemoryRunnerWorkflowTest(CallHttpTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class DoExecutionTest : InMemoryRunnerWorkflowTest(DoTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ExportContextExecutionTest : InMemoryRunnerWorkflowTest(ExportContextTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForExecutionTest : InMemoryRunnerWorkflowTest(ForTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkExecutionTest : InMemoryRunnerWorkflowTest(ForkTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class IfExecutionTest : InMemoryRunnerWorkflowTest(IfConditionTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RunScriptExecutionTest : InMemoryRunnerWorkflowTest(RunScriptTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RunShellExecutionTest : InMemoryRunnerWorkflowTest(RunShellTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RunWorkflowExecutionTest : InMemoryRunnerWorkflowTest(RunWorkflowTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class SetExecutionTest : InMemoryRunnerWorkflowTest(SetTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class SwitchExecutionTest : InMemoryRunnerWorkflowTest(SwitchTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class TryExecutionTest : InMemoryRunnerWorkflowTest(TryTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class WaitExecutionTest : InMemoryRunnerWorkflowTest(WaitTestCases.cases)
