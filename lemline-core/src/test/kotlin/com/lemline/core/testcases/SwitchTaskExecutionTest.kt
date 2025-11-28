// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for SwitchTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SwitchTaskExecutionTest : AbstractWorkflowExecutionTest(SwitchTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
