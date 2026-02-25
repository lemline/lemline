// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestExecutor
import jakarta.inject.Inject

/**
 * Abstract base class for broker-based workflow execution tests.
 *
 * This class uses JUnit5's dynamic tests to execute shared [WorkflowTestCase]s
 * from lemline-core's testFixtures against real message brokers (Kafka/RabbitMQ).
 *
 * Unlike [InMemoryWorkflowTest] which uses in-memory channels,
 * this class tests workflows through actual broker infrastructure.
 *
 * Subclasses must:
 * - Use `@QuarkusTest` and appropriate `@TestProfile` annotations (e.g., KafkaTestCaseProfile)
 * - Provide the list of test cases to execute
 * - Optionally specify tags to exclude
 *
 * @param testCases The list of test cases to execute
 * @param excludeTags Tags to exclude from execution (e.g., "external", "slow")
 */
internal abstract class BrokerWorkflowTest(
    testCases: List<WorkflowTestCase>,
    excludeTags: Set<String> = emptySet()
) : AbstractWorkflowTest(testCases, excludeTags) {

    @Inject
    internal lateinit var executor: BrokerWorkflowTestExecutor

    override fun getExecutor(): WorkflowTestExecutor = executor
}
