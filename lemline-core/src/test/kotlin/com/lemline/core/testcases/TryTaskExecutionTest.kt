// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.TryTaskTestCases
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for TryTask execution using FullOrchestrator.
 */
@ExperimentalTime
class TryTaskExecutionTest : AbstractWorkflowExecutionTest(TryTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
