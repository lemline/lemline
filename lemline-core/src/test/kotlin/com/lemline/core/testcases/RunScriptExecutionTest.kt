// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for Script execution using FullOrchestrator.
 */
@ExperimentalTime
class RunScriptExecutionTest : AbstractWorkflowExecutionTest(RunScriptTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
