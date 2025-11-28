// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for SetTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SetTaskExecutionTest : AbstractWorkflowExecutionTest(SetTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
