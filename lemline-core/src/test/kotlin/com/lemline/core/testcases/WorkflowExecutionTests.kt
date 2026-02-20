// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.impl.FullOrchestratorTestExecutor

/**
 * Tests for function call execution using FullOrchestrator.
 */
class CallFunctionExecutionTest : AbstractWorkflowExecutionTest(CallFunctionTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for HTTP call execution using FullOrchestrator.
 */
class CallHttpExecutionTest : AbstractWorkflowExecutionTest(CallHttpTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for DoTask execution using FullOrchestrator.
 */
class DoTaskExecutionTest : AbstractWorkflowExecutionTest(DoTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for export.as directive using FullOrchestrator.
 */
class ExportContextExecutionTest : AbstractWorkflowExecutionTest(ExportContextTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForTask execution using FullOrchestrator.
 */
class ForTaskExecutionTest : AbstractWorkflowExecutionTest(ForTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForkTask execution using FullOrchestrator.
 */
class ForkTaskExecutionTest : AbstractWorkflowExecutionTest(ForkTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for if condition execution using FullOrchestrator.
 */
class IfConditionExecutionTest : AbstractWorkflowExecutionTest(IfConditionTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Listen task execution using FullOrchestrator.
 */
class ListenExecutionTest : AbstractWorkflowExecutionTest(ListenTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Script execution using FullOrchestrator.
 */
class RunScriptExecutionTest : AbstractWorkflowExecutionTest(RunScriptTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Shell execution using FullOrchestrator.
 */
class RunShellExecutionTest : AbstractWorkflowExecutionTest(RunShellTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for RunWorkflow (sub-workflow) execution using FullOrchestrator.
 */
class RunWorkflowExecutionTest : AbstractWorkflowExecutionTest(RunWorkflowTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SetTask execution using FullOrchestrator.
 */
class SetTaskExecutionTest : AbstractWorkflowExecutionTest(SetTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SwitchTask execution using FullOrchestrator.
 */
class SwitchTaskExecutionTest : AbstractWorkflowExecutionTest(SwitchTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for TryTask execution using FullOrchestrator.
 */
class TryTaskExecutionTest : AbstractWorkflowExecutionTest(TryTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Wait task execution using FullOrchestrator.
 */
class WaitExecutionTest : AbstractWorkflowExecutionTest(WaitTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}
