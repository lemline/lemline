// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.core.activities.mock.MockConfiguration
import io.cloudevents.CloudEvent

interface WorkflowTestExecutor {

    suspend fun execute(
        yaml: String,
        input: JsonNode = ObjectNode(emptyMap()),
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0",
        dependencies: List<WorkflowDependency> = emptyList(),
        mockConfig: MockConfiguration = MockConfiguration.empty(),
        cloudEvents: List<CloudEvent> = emptyList(),
        validateDefinition: Boolean = true,
        validateLifecycleEvents: ((List<CloudEvent>) -> String?)? = null,
        rebuildCachesOnListen: Boolean = false
    ): WorkflowTestResult
}
