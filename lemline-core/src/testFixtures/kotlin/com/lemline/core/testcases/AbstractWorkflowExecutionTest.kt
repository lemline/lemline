// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.JsonElement

/**
 * Abstract base class for workflow execution tests.
 *
 * Subclasses provide:
 * - A [WorkflowTestExecutor] implementation
 * - A list of [WorkflowTestCase]s to run
 *
 * This enables running the same test cases against different execution backends:
 * - In-memory orchestrator for fast unit tests
 * - Real infrastructure (Kafka + Postgres) for E2E tests
 *
 * @param testCases The list of test cases to execute
 * @param excludeTags Tags to exclude from execution (e.g., "slow", "external")
 */
abstract class AbstractWorkflowExecutionTest(
    testCases: List<WorkflowTestCase>,
    excludeTags: Set<String> = emptySet()
) : FunSpec() {

    /**
     * Create the executor for this test class.
     * Called once per test case.
     */
    abstract fun createExecutor(): WorkflowTestExecutor

    init {
        testCases
            .filter { case -> case.tags.none { it in excludeTags } }
            .forEach { case ->
                // Check for platform-specific tests
                val testConfig = when {
                    "unix-only" in case.tags -> {
                        val isUnix = System.getProperty("os.name").lowercase().let {
                            it.contains("mac") || it.contains("linux")
                        }
                        if (!isUnix) null else case
                    }

                    "windows-only" in case.tags -> {
                        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                        if (!isWindows) null else case
                    }

                    else -> case
                }

                if (testConfig != null) {
                    test(case.name).config(timeout = case.timeout) {
                        val executor = createExecutor()
                        val result = executor.execute(
                            yaml = case.yaml,
                            input = case.input,
                            dependencies = case.dependencies,
                            mockConfig = case.mockConfig,
                            cloudEvents = case.cloudEvents
                        )

                        // Run custom validation if provided
                        val validationError = case.validate(result)
                        if (validationError != null) {
                            throw AssertionError("Validation failed for '${case.name}': $validationError\nResult: $result")
                        }
                    }
                }
            }
    }
}

/**
 * DSL helpers for creating test case validators.
 */
object WorkflowTestValidators {

    /**
     * Validates that the workflow succeeds (output can be anything).
     */
    fun expectSuccess(): (WorkflowTestResult) -> String? = { result ->
        when (result) {
            is WorkflowTestResult.Success -> null
            is WorkflowTestResult.Failure -> "Expected success but got failure: ${result.error}"
        }
    }

    /**
     * Validates that the workflow fails with an error containing the given message.
     * Checks both the error message and the exception's toString() representation.
     */
    fun expectErrorContaining(message: String): (WorkflowTestResult) -> String? = { result ->
        when (result) {
            is WorkflowTestResult.Success -> "Expected failure containing '$message' but got success: ${result.output}"
            is WorkflowTestResult.Failure -> {
                // Check the error message first
                val errorContains = result.error.contains(message)
                // Also check the full exception representation (including nested error types)
                val exceptionContains = result.exception?.toString()?.contains(message) == true
                if (!errorContains && !exceptionContains) {
                    "Expected error containing '$message' but got: ${result.error}"
                } else null
            }
        }
    }

    /**
     * Validates the successful output with a custom predicate.
     */
    fun expectOutputMatching(
        description: String,
        predicate: (JsonElement) -> Boolean
    ): (WorkflowTestResult) -> String? = { result ->
        when (result) {
            is WorkflowTestResult.Success -> {
                if (!predicate(result.output)) {
                    "Output did not match: $description\nActual output: ${result.output}"
                } else null
            }

            is WorkflowTestResult.Failure -> "Expected success but got failure: ${result.error}"
        }
    }
}
