// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for ForTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForTaskExecutionTest : AbstractWorkflowExecutionTest(ForTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
