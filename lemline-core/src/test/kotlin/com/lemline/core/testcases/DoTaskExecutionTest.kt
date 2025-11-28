// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.testcases.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.FullOrchestratorExecutor
import com.lemline.core.testcases.WorkflowTestExecutor
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
