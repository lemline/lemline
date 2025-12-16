// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.testcases.inMemory

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
internal class CallHttpExecutionTest : InMemoryWorkflowTest(CallHttpTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DoExecutionTest : InMemoryWorkflowTest(DoTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class ExportContextExecutionTest : InMemoryWorkflowTest(ExportContextTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class ForExecutionTest : InMemoryWorkflowTest(ForTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class ForkExecutionTest : InMemoryWorkflowTest(ForkTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class IfExecutionTest : InMemoryWorkflowTest(IfConditionTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class RunScriptExecutionTest : InMemoryWorkflowTest(RunScriptTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class RunShellExecutionTest : InMemoryWorkflowTest(RunShellTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class RunWorkflowExecutionTest : InMemoryWorkflowTest(RunWorkflowTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class SetExecutionTest : InMemoryWorkflowTest(SetTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class SwitchExecutionTest : InMemoryWorkflowTest(SwitchTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class TryExecutionTest : InMemoryWorkflowTest(TryTaskTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class WaitExecutionTest : InMemoryWorkflowTest(WaitTestCases.cases)

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class ListenExecutionTest : InMemoryWorkflowTest(ListenTestCases.cases)
