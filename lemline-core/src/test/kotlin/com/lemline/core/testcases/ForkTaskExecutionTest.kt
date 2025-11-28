// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.ForkTaskTestCases
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for ForkTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForkTaskExecutionTest : AbstractWorkflowExecutionTest(ForkTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
