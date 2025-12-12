package com.lemline.runner.testcases.bases

import com.lemline.core.testcases.WorkflowTestCase
import com.lemline.core.testcases.WorkflowTestExecutor
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Abstract base class for broker-based workflow execution tests.
 *
 * This class uses JUnit5's dynamic tests to execute shared [com.lemline.core.testcases.WorkflowTestCase]s
 * from lemline-core's testFixtures against real message brokers (Kafka/RabbitMQ).
 *
 * Unlike [com.lemline.runner.testcases.inMemory.InMemoryWorkflowTest] which uses in-memory channels,
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
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class BrokerWorkflowTest(
    testCases: List<WorkflowTestCase>,
    excludeTags: Set<String> = emptySet()
) : AbstractWorkflowTest(testCases, excludeTags) {

    @Inject
    private lateinit var executor: BrokerWorkflowTestExecutor

    override fun getExecutor(): WorkflowTestExecutor = executor
}
