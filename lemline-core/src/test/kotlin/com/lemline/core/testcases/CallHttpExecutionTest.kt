// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for HTTP call execution using FullOrchestrator.
 */
@ExperimentalTime
class CallHttpExecutionTest : AbstractWorkflowExecutionTest(CallHttpTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
