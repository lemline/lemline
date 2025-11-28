// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.WaitTestCases
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for Wait task execution using FullOrchestrator.
 */
@ExperimentalTime
class WaitExecutionTest : AbstractWorkflowExecutionTest(WaitTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
