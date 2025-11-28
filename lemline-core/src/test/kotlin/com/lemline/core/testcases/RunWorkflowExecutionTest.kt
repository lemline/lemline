// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for RunWorkflow (sub-workflow) execution using FullOrchestrator.
 */
@ExperimentalTime
class RunWorkflowExecutionTest : AbstractWorkflowExecutionTest(RunWorkflowTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
