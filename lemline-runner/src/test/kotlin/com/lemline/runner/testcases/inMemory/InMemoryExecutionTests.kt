// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.CallFunctionTestCases
import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.ExportContextTestCases
import com.lemline.core.testcases.ForTaskTestCases
import com.lemline.core.testcases.ForkTaskTestCases
import com.lemline.core.testcases.IfConditionTestCases
import com.lemline.core.testcases.ListenTestCases
import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.core.testcases.RunShellTestCases
import com.lemline.core.testcases.RunWorkflowTestCases
import com.lemline.core.testcases.SetTaskTestCases
import com.lemline.core.testcases.SwitchTaskTestCases
import com.lemline.core.testcases.TryTaskTestCases
import com.lemline.core.testcases.WaitTestCases
import com.lemline.runner.testcases.bases.InMemoryWorkflowTest
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/**
 * In-memory workflow execution tests using runner messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * in-memory channels with manual routing between command/event handlers.
 */

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryCallFunctionExecutionTest : InMemoryWorkflowTest(CallFunctionTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryCallHttpExecutionTest : InMemoryWorkflowTest(CallHttpTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryDoExecutionTest : InMemoryWorkflowTest(DoTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryExportContextExecutionTest : InMemoryWorkflowTest(ExportContextTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryForExecutionTest : InMemoryWorkflowTest(ForTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryForkExecutionTest : InMemoryWorkflowTest(ForkTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryIfExecutionTest : InMemoryWorkflowTest(IfConditionTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryRunScriptExecutionTest : InMemoryWorkflowTest(RunScriptTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryRunShellExecutionTest : InMemoryWorkflowTest(RunShellTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryRunWorkflowExecutionTest : InMemoryWorkflowTest(RunWorkflowTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemorySetExecutionTest : InMemoryWorkflowTest(SetTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemorySwitchExecutionTest : InMemoryWorkflowTest(SwitchTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryTryExecutionTest : InMemoryWorkflowTest(TryTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryWaitExecutionTest : InMemoryWorkflowTest(WaitTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class InMemoryListenExecutionTest : InMemoryWorkflowTest(ListenTestCases.cases)
