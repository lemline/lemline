// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.core.workflows.WorkflowCache
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DefinitionModelTest {
    @Test
    fun `timer schedules remain loadable after conversion to a stored definition`() {
        for (schedule in listOf("every: PT1M", "after: PT1M", "cron: '0 * * * *'")) {
            val workflow = WorkflowCache.parseYaml("""
                document:
                  dsl: '1.0.3'
                  namespace: test
                  name: timer
                  version: '1.0.0'
                schedule:
                  $schedule
                do:
                  - result:
                      set: { value: ok }
            """.trimIndent())
            val stored = DefinitionModel.from(workflow)
            val restored = WorkflowCache.parseYaml(stored.definition)
            assertEquals(stored, DefinitionModel.from(restored))
        }
    }
}
