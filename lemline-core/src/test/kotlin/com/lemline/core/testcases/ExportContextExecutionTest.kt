// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for export.as directive using FullOrchestrator.
 */
@ExperimentalTime
class ExportContextExecutionTest : AbstractWorkflowExecutionTest(ExportContextTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
