package com.lemline.core.testcases.impl

import com.lemline.core.activities.mock.MockConfiguration
import io.cloudevents.CloudEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface WorkflowTestExecutor {

    suspend fun execute(
        yaml: String,
        input: JsonElement = JsonObject(emptyMap()),
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0",
        dependencies: List<WorkflowDependency> = emptyList(),
        mockConfig: MockConfiguration = MockConfiguration.Companion.empty(),
        cloudEvents: List<CloudEvent> = emptyList(),
        validateDefinition: Boolean = true
    ): WorkflowTestResult
}
