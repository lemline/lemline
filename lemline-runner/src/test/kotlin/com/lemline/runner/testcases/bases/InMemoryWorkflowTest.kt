// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestExecutor
import jakarta.inject.Inject

/**
 * Abstract base class for in-memory workflow execution tests.
 *
 * This class uses JUnit5's dynamic tests to execute shared [com.lemline.core.testcases.impl.WorkflowTestCase]s
 * from lemline-core's testFixtures using in-memory message channels.
 *
 * Unlike broker-based tests, this uses [InMemoryWorkflowTestExecutor] which routes
 * messages through in-memory channels for fast test execution.
 *
 * Subclasses must:
 * - Use `@QuarkusTest` and appropriate `@TestProfile` annotations
 * - Provide the list of test cases to execute
 * - Optionally specify tags to exclude
 *
 * @param testCases The list of test cases to execute
 * @param excludeTags Tags to exclude from execution (e.g., "external", "slow")
 */
internal abstract class InMemoryWorkflowTest(
    testCases: List<WorkflowTestCase>,
    excludeTags: Set<String> = emptySet()
) : AbstractWorkflowTest(testCases, excludeTags) {

    @Inject
    lateinit var executor: InMemoryWorkflowTestExecutor

    override fun getExecutor(): WorkflowTestExecutor = executor
}
