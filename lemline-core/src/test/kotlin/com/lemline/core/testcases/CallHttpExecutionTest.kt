// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for HTTP call execution using FullOrchestrator.
 */
@ExperimentalTime
class CallHttpExecutionTest : AbstractWorkflowExecutionTest(CallHttpTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
