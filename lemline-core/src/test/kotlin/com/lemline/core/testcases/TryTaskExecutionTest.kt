// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for TryTask execution using FullOrchestrator.
 */
@ExperimentalTime
class TryTaskExecutionTest : AbstractWorkflowExecutionTest(TryTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
