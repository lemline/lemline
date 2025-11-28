// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.ExportContextTestCases
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.WorkflowTestExecutor
import kotlin.time.ExperimentalTime

/**
 * Tests for export.as directive using FullOrchestrator.
 */
@ExperimentalTime
class ExportContextExecutionTest : AbstractWorkflowExecutionTest(ExportContextTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
