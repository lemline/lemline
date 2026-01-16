// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.AbstractWorkflowExecutionTest
import com.lemline.core.testcases.impl.FullOrchestratorTestExecutor
import com.lemline.core.testcases.impl.TestMocks
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests for function call execution using FullOrchestrator.
 */
@ExperimentalTime
class CallFunctionExecutionTest : AbstractWorkflowExecutionTest(CallFunctionTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for HTTP call execution using FullOrchestrator.
 */
@ExperimentalTime
class CallHttpExecutionTest : AbstractWorkflowExecutionTest(CallHttpTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for DoTask execution using FullOrchestrator.
 */
@ExperimentalTime
class DoTaskExecutionTest : AbstractWorkflowExecutionTest(DoTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for export.as directive using FullOrchestrator.
 */
@ExperimentalTime
class ExportContextExecutionTest : AbstractWorkflowExecutionTest(ExportContextTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForTaskExecutionTest : AbstractWorkflowExecutionTest(ForTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for ForkTask execution using FullOrchestrator.
 */
@ExperimentalTime
class ForkTaskExecutionTest : AbstractWorkflowExecutionTest(ForkTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for if condition execution using FullOrchestrator.
 */
@ExperimentalTime
class IfConditionExecutionTest : AbstractWorkflowExecutionTest(IfConditionTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Listen task execution using FullOrchestrator.
 */
@ExperimentalTime
class ListenExecutionTest : AbstractWorkflowExecutionTest(ListenTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

@ExperimentalTime
class TestExecutionTest : AbstractWorkflowExecutionTest(
    listOf(

        WorkflowTestCase(
            name = "listen foreach with outer try-catch stops on error and skips remaining events",
            cloudEvents = listOf(
                TestMocks.reading1Event,
                TestMocks.reading2Event,  // Second event will trigger failure
                TestMocks.reading3ThresholdEvent  // Third event should NOT be processed
            ),
            yaml = $$"""
                do:
                  - setProcessedCount:
                      set:
                        processedCount: 0
                      export:
                        as: ${ . }
                  - handleReadings:
                      try:
                        - collectReadings:
                            listen:
                              to:
                                any:
                                  - with:
                                      type: sensor.reading
                                until: . | any(.value > 100)
                            foreach:
                              do:
                                - checkValue:
                                    if: ${ .readingId == 2 }
                                    raise:
                                      error:
                                        type: https://serverlessworkflow.io/errors/processing
                                        status: 400
                                        title: Processing failed
                                - incrementCount:
                                    set:
                                      processedCount: ${ $context.processedCount + 1 }
                                    export:
                                      as: ${ . }
                      catch:
                        as: caughtError
                        do:
                          - returnResult:
                              set:
                                failed: true
                                errorType: ${ $caughtError.type }
                                processedBeforeError: ${ $context.processedCount }
            """.trimIndent(),
            tags = setOf("listen", "cloudevents", "foreach", "error", "fail-fast"),
            validate = expectOutput(
                buildJsonObject {
                    put("failed", true)
                    put("errorType", "https://serverlessworkflow.io/errors/processing")
                    put("processedBeforeError", 1)
                }
            ),
            timeout = 100.seconds
        )
    )
) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Script execution using FullOrchestrator.
 */
@ExperimentalTime
class RunScriptExecutionTest : AbstractWorkflowExecutionTest(RunScriptTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Shell execution using FullOrchestrator.
 */
@ExperimentalTime
class RunShellExecutionTest : AbstractWorkflowExecutionTest(RunShellTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for RunWorkflow (sub-workflow) execution using FullOrchestrator.
 */
@ExperimentalTime
class RunWorkflowExecutionTest : AbstractWorkflowExecutionTest(RunWorkflowTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SetTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SetTaskExecutionTest : AbstractWorkflowExecutionTest(SetTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for SwitchTask execution using FullOrchestrator.
 */
@ExperimentalTime
class SwitchTaskExecutionTest : AbstractWorkflowExecutionTest(SwitchTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for TryTask execution using FullOrchestrator.
 */
@ExperimentalTime
class TryTaskExecutionTest : AbstractWorkflowExecutionTest(TryTaskTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}

/**
 * Tests for Wait task execution using FullOrchestrator.
 */
@ExperimentalTime
class WaitExecutionTest : AbstractWorkflowExecutionTest(WaitTestCases.cases) {
    override fun createExecutor() = FullOrchestratorTestExecutor()
}
