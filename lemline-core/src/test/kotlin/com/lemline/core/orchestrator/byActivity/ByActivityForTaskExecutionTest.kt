// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.byActivity

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.bases.ForTaskExecutionTest
import com.lemline.core.orchestrator.executeActivityByActivityWorkflow
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal class ByActivityForTaskExecutionTest : ForTaskExecutionTest() {
    init {
        afterEach {
            DefinitionCache.clear()
        }
    }

    override suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String
    ): JsonElement = executeActivityByActivityWorkflow(yaml, input, namespace, name, version)
}
