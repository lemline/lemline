// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LifecycleEventTypeTest {
    @Test
    fun `emits current workflow and task event types and recognizes the legacy namespace`() {
        assertEquals("org.open-workflow-specification.workflow.started.v1", LifecycleEventType.WORKFLOW_STARTED.type)
        assertEquals("org.open-workflow-specification.task.started.v1", LifecycleEventType.TASK_STARTED.type)
        assertEquals(
            listOf("org.open-workflow-specification.workflow.started.v1", "io.serverlessworkflow.workflow.started.v1"),
            LifecycleEventType.WORKFLOW_STARTED.acceptedTypes,
        )
    }
}
