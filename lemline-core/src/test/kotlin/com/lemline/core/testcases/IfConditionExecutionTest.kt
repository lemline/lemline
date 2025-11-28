// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.IfConditionTestCases
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for if condition execution using FullOrchestrator.
 */
@ExperimentalTime
class IfConditionExecutionTest : AbstractWorkflowExecutionTest(IfConditionTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
