// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.lemline.common.logger.logger
import com.lemline.core.functions.FunctionResolutionException
import com.lemline.core.functions.FunctionResolver
import io.serverlessworkflow.api.types.Task

/**
 * Mock function resolver for testing.
 *
 * Resolves remote function references from [mockConfig.functionDefinitions].
 *
 * ## Usage
 *
 * ```kotlin
 * val resolver = MockFunctionResolver(MockConfiguration(
 *     functionDefinitions = mapOf(
 *         "https://example.com/validate.yaml" to Task().apply {
 *             callTask = CallTask().apply {
 *                 callHTTP = CallHTTP().apply {
 *                     with = CallHTTPWith().apply {
 *                         method = "POST"
 *                         endpoint = "https://api.validation.com"
 *                     }
 *                 }
 *             }
 *         }
 *     )
 * ))
 * ```
 *
 * ## Function Reference Types
 *
 * - `https://example.com/func.yaml` → URL-based remote function
 * - `myFunc:1.0@catalog` → Catalog-based remote function
 *
 * @property mockConfig Configuration containing remote function definitions
 */
class MockFunctionResolver(
    private val mockConfig: MockConfiguration = MockConfiguration.empty()
) : FunctionResolver {

    private val logger = logger()

    /**
     * Resolves a remote function reference to its task definition.
     *
     * @param functionRef The function reference (URL or catalog ref)
     * @return The resolved [Task] definition
     * @throws FunctionResolutionException if function cannot be resolved
     */
    override suspend fun resolve(functionRef: String): Task {
        logger.debug { "Resolving remote function: $functionRef" }

        val task = mockConfig.getFunctionDefinition(functionRef)
            ?: throw FunctionResolutionException(
                "Remote function '$functionRef' not found in mock configuration. " +
                    "Add it to functionDefinitions in MockConfiguration."
            )

        logger.debug { "Resolved remote function from mock: $functionRef" }
        return task
    }
}
