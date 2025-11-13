// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Integration tests for HTTP call execution using CompleteOrchestrator.
 *
 * Tests HTTP calls to real external services (JSONPlaceholder API) to verify:
 * - Basic HTTP method support (GET, POST, PUT, DELETE)
 * - Query parameter handling
 * - Request body handling
 * - Header support
 * - Response parsing
 */
@ExperimentalTime
abstract class AbstractExecutionTest(body: FunSpec.() -> Unit = {}) : FunSpec(body) {

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
