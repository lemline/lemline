// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for Script execution using FullOrchestrator.
 */
@ExperimentalTime
class RunScriptExecutionTest : AbstractWorkflowExecutionTest(RunScriptTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
