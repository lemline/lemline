// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.core.testcases.WorkflowTestCase
import com.lemline.core.testcases.WorkflowTestExecutor
import com.lemline.runner.testcases.PlatformUtils
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Abstract base class for workflow execution tests using JUnit5 dynamic tests.
 *
 * This class provides common test factory logic for executing shared [WorkflowTestCase]s
 * from lemline-core's testFixtures. Subclasses specify:
 * - Which test cases to run
 * - Which tags to exclude
 * - The executor to use (injected via [getExecutor])
 *
 * Subclasses must:
 * - Use `@QuarkusTest` and appropriate `@TestProfile` annotations
 * - Override [getExecutor] to return the appropriate injected executor
 *
 * @param testCases The list of test cases to execute
 * @param excludeTags Tags to exclude from execution (e.g., "external", "slow")
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractWorkflowTest(
    private val testCases: List<WorkflowTestCase>,
    private val excludeTags: Set<String> = emptySet()
) {

    /**
     * Returns the workflow test executor to use for running tests.
     * Subclasses should return an injected executor instance.
     */
    protected abstract fun getExecutor(): WorkflowTestExecutor

    @TestFactory
    fun workflowTests(): List<DynamicTest> {
        return testCases
            .filter { case -> case.tags.none { it in excludeTags } }
            .filter { case -> PlatformUtils.shouldRunOnCurrentPlatform(case) }
            .map { case ->
                DynamicTest.dynamicTest(case.name) {
                    runBlocking {
                        val result = getExecutor().execute(
                            yaml = case.yaml,
                            input = case.input,
                            dependencies = case.dependencies
                        )

                        val validationError = case.validate(result)
                        if (validationError != null) {
                            throw AssertionError(
                                "Validation failed for '${case.name}': $validationError\nResult: $result"
                            )
                        }
                    }
                }
            }
    }
}
