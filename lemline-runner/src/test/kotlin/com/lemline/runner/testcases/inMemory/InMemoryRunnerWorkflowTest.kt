package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.WorkflowTestCase
import com.lemline.runner.testcases.RunnerWorkflowTestExecutor
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Abstract base class for runner workflow execution tests.
 *
 * This class uses JUnit5's dynamic tests to execute shared [com.lemline.core.testcases.WorkflowTestCase]s
 * from lemline-core's testFixtures against the runner's messaging infrastructure.
 *
 * Subclasses must:
 * - Use `@QuarkusTest` and appropriate `@TestProfile` annotations
 * - Provide the list of test cases to execute
 * - Optionally specify tags to exclude
 *
 * @param testCases The list of test cases to execute
 * @param excludeTags Tags to exclude from execution (e.g., "external", "slow")
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class InMemoryRunnerWorkflowTest(
    private val testCases: List<WorkflowTestCase>,
    private val excludeTags: Set<String> = emptySet()
) {

    @Inject
    lateinit var executor: RunnerWorkflowTestExecutor

    @TestFactory
    fun workflowTests(): List<DynamicTest> {
        return testCases
            .filter { case -> case.tags.none { it in excludeTags } }
            .filter { case -> shouldRunOnCurrentPlatform(case) }
            .map { case ->
                DynamicTest.dynamicTest(case.name) {
                    runBlocking {
                        val result = executor.execute(
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

    private fun shouldRunOnCurrentPlatform(case: WorkflowTestCase): Boolean {
        val osName = System.getProperty("os.name").lowercase()

        return when {
            "unix-only" in case.tags -> {
                osName.contains("mac") || osName.contains("linux")
            }

            "windows-only" in case.tags -> {
                osName.contains("windows")
            }

            else -> true
        }
    }
}
