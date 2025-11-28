// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for Shell execution using FullOrchestrator.
 */
@ExperimentalTime
class RunShellExecutionTest : AbstractWorkflowExecutionTest(RunShellTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
