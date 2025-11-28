// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for Wait task execution using FullOrchestrator.
 */
@ExperimentalTime
class WaitExecutionTest : AbstractWorkflowExecutionTest(WaitTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
