// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.functions

import io.serverlessworkflow.api.types.Task

/**
 * Resolves remote function references to their task definitions.
 *
 * Remote function references come in two forms:
 * 1. **URL**: Direct URL to function definition (e.g., `https://example.com/function.yaml`)
 * 2. **Catalog**: Catalog reference (e.g., `log:1.0.0@default`)
 *
 * Named functions (from workflow's `use.functions`) are resolved directly by the caller
 * since they're a simple map lookup.
 *
 * ## Implementation Notes
 *
 * - [com.lemline.core.activities.mock.MockFunctionResolver] is used in tests with mocked definitions
 * - Production resolver (in lemline-runner) fetches URLs via HTTP
 *
 * @see FunctionExecutor
 */
interface FunctionResolver {

    /**
     * Resolves a remote function reference to its task definition.
     *
     * @param functionRef The function reference (URL or catalog ref)
     * @return The resolved [Task] definition
     * @throws FunctionResolutionException if the function cannot be resolved
     */
    suspend fun resolve(functionRef: String): Task
}

/**
 * Exception thrown when a function cannot be resolved.
 */
class FunctionResolutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
