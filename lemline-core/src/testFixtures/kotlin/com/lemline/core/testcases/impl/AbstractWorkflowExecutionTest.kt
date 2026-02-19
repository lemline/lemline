// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases.impl

import io.kotest.core.spec.style.FunSpec
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
                    if (case.skip) {
                        xtest(case.name) { }
                    } else {
                        test(case.name).config(timeout = case.timeout) {
                            val executor = createExecutor()
                            val result = executor.execute(
                                yaml = case.yaml,
                                input = case.input,
                                dependencies = case.dependencies,
                                mockConfig = case.mockConfig,
                                cloudEvents = case.cloudEvents,
                                validateDefinition = case.validateDefinition
                            )

                            val validationError = case.validate(result)
                            if (validationError != null) {
                                throw AssertionError("Validation failed for '${case.name}': $validationError")
                            }
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
     * Validates that the workflow output equals the expected value exactly.
     * On failure, displays both expected and actual values for easy debugging.
     */
    fun expectOutput(expected: JsonElement): (WorkflowTestResult) -> String? = { result ->
        when (result) {
            is WorkflowTestResult.Success -> {
                if (!jsonSemanticallyEquals(result.output, expected)) {
                    "Output mismatch:\n  Expected: $expected\n  Actual:   ${result.output}"
                } else null
            }

            is WorkflowTestResult.Failure -> "Expected success but got failure: ${result.error}"
        }
    }

    /**
     * Validates the successful output with a custom predicate.
     */
    fun validateOutput(
        predicate: (JsonElement) -> Boolean
    ): (WorkflowTestResult) -> String? = { result ->
        when (result) {
            is WorkflowTestResult.Success -> {
                if (!predicate(result.output)) {
                    "Output mismatch:\n   Actual:   ${result.output}"
                } else null
            }

            is WorkflowTestResult.Failure -> "Expected success but got failure: ${result.error}"
        }
    }

    private fun jsonSemanticallyEquals(actual: JsonElement, expected: JsonElement): Boolean = when {
        actual is JsonObject && expected is JsonObject -> {
            actual.keys == expected.keys &&
                actual.all { (key, value) -> jsonSemanticallyEquals(value, expected.getValue(key)) }
        }

        actual is JsonArray && expected is JsonArray -> {
            actual.size == expected.size &&
                actual.indices.all { index -> jsonSemanticallyEquals(actual[index], expected[index]) }
        }

        actual is JsonPrimitive && expected is JsonPrimitive -> {
            jsonPrimitivesSemanticallyEqual(actual, expected)
        }

        else -> actual == expected
    }

    private fun jsonPrimitivesSemanticallyEqual(actual: JsonPrimitive, expected: JsonPrimitive): Boolean {
        if (actual.isString || expected.isString) {
            return actual.isString && expected.isString && actual.content == expected.content
        }

        val actualBoolean = actual.booleanOrNull
        val expectedBoolean = expected.booleanOrNull
        if (actualBoolean != null || expectedBoolean != null) {
            return actualBoolean != null && expectedBoolean != null && actualBoolean == expectedBoolean
        }

        val actualNumber = actual.toBigDecimalOrNull()
        val expectedNumber = expected.toBigDecimalOrNull()
        if (actualNumber != null || expectedNumber != null) {
            return actualNumber != null && expectedNumber != null && actualNumber.compareTo(expectedNumber) == 0
        }

        return actual.content == expected.content
    }

    private fun JsonPrimitive.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(content) }.getOrNull()
}
