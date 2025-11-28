// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for if condition execution using FullOrchestrator.
 */
@ExperimentalTime
class IfConditionExecutionTest : AbstractWorkflowExecutionTest(IfConditionTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
