// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for HTTP call execution using FullOrchestrator.
 */
@ExperimentalTime
class CallHttpExecutionTest : AbstractWorkflowExecutionTest(CallHttpTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for DoTask execution using FullOrchestrator.
 */
@ExperimentalTime
class DoTaskExecutionTest : AbstractWorkflowExecutionTest(DoTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for export.as directive using FullOrchestrator.
 */
@ExperimentalTime
class ExportContextExecutionTest : AbstractWorkflowExecutionTest(ExportContextTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForTaskExecutionTest : AbstractWorkflowExecutionTest(ForTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForkTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForkTaskExecutionTest : AbstractWorkflowExecutionTest(ForkTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for if condition execution using FullOrchestrator.
 */
@ExperimentalTime
class IfConditionExecutionTest : AbstractWorkflowExecutionTest(IfConditionTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Listen task execution using FullOrchestrator.
 */
@ExperimentalTime
class ListenExecutionTest : AbstractWorkflowExecutionTest(ListenTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Script execution using FullOrchestrator.
 */
@ExperimentalTime
class RunScriptExecutionTest : AbstractWorkflowExecutionTest(RunScriptTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Shell execution using FullOrchestrator.
 */
@ExperimentalTime
class RunShellExecutionTest : AbstractWorkflowExecutionTest(RunShellTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for RunWorkflow (sub-workflow) execution using FullOrchestrator.
 */
@ExperimentalTime
class RunWorkflowExecutionTest : AbstractWorkflowExecutionTest(RunWorkflowTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SetTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SetTaskExecutionTest : AbstractWorkflowExecutionTest(SetTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SwitchTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SwitchTaskExecutionTest : AbstractWorkflowExecutionTest(SwitchTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for TryTask execution using FullOrchestrator.
 */
@ExperimentalTime
class TryTaskExecutionTest : AbstractWorkflowExecutionTest(TryTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Wait task execution using FullOrchestrator.
 */
@ExperimentalTime
class WaitExecutionTest : AbstractWorkflowExecutionTest(WaitTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}
