// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.secrets

import com.lemline.common.json.*
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.system.System
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SecretsTest {

    @AfterEach
    fun tearDown() {
        System.restoreEnv()
        WorkflowCache.clear()
    }

    @Test
    fun `resolves only declared secrets from the process environment`() {
        val workflow = WorkflowCache.parseYamlAndPut("""
            document:
              dsl: '1.0.0'
              namespace: test
              name: secrets-test
              version: '1.0.0'
            use:
              secrets:
                - TEST_TOKEN
                - TEST_CONFIG
            do:
              - first:
                  set:
                    value: ok
        """.trimIndent())
        System.setEnv("TEST_TOKEN", "first-value")
        System.setEnv("TEST_CONFIG", "{\"key\":\"value\"}")
        System.setEnv("UNDECLARED", "hidden")

        val resolved = Secrets.getForWorkflow(workflow)
        assertEquals(jsonValue("first-value"), resolved["TEST_TOKEN"])
        assertEquals(jsonValue("value"), resolved.getValue("TEST_CONFIG").jsonObject["key"])
        assertFalse(resolved.has("UNDECLARED"))
    }
}
