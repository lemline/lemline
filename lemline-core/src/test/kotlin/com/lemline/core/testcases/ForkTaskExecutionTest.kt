// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for ForkTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForkTaskExecutionTest : AbstractWorkflowExecutionTest(ForkTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
