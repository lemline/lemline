// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import kotlin.time.ExperimentalTime

/**
 * Tests for DoTask execution using FullOrchestrator.
 *
 * Uses shared test cases from [DoTaskTestCases] that can also be run
 * against the real runner infrastructure for E2E testing.
 */
@ExperimentalTime
class DoTaskExecutionTest : AbstractWorkflowExecutionTest(DoTaskTestCases.cases) {

    override fun createExecutor(): WorkflowTestExecutor = FullOrchestratorExecutor()
}
