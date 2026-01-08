// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestExecutor
import com.lemline.runner.testcases.PlatformUtils
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractWorkflowTest(
    private val testCases: List<WorkflowTestCase>,
    private val excludeTags: Set<String> = emptySet()
) {

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
                            dependencies = case.dependencies,
                            mockConfig = case.mockConfig,
                            cloudEvents = case.cloudEvents,
                            validateDefinition = case.validateDefinition
                        )

                        val validationError = case.validate(result)
                        if (validationError != null) {
                            throw AssertionError(
                                "Validation failed for '${case.name}': $validationError"
                            )
                        }
                    }
                }
            }
    }
}
