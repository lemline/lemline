// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.json.parseJson
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ListenerCorrelationCompatibilityTest {
    @Test
    fun `expected and event correlation documents use identical historical escaping`() {
        WorkflowCache.parseYamlAndPut("""
            document:
              dsl: '1.0.3'
              namespace: compatibility
              name: escaped-correlation
              version: '1.0.0'
            do:
              - receive:
                  listen:
                    to:
                      one:
                        with: { type: escaped-correlation }
                        correlate:
                          key: { from: '${'$'}{ .value }', expect: '${'$'}{ .expected }' }
        """.trimIndent())
        val config = ListenConfig(
            strategy = ListenStrategy.ONE,
            filters = listOf(EventFilter(correlations = mapOf("key" to CorrelationDef(".value", "\${ .expected }")))),
            correlationContext = parseJson("""{"expected":"\u001b"}"""),
        )
        val expected = ListenerService().calculateCorrelationValues(config)
        val event = CloudEventBuilder.v1().withId("escaped").withSource(URI("https://test.example"))
            .withType("escaped-correlation").withData("application/json", """{"value":"\u001b"}""".toByteArray()).build()
        val actual = DefinitionListenService().findMatchingListenTasks(event).single { it.workflowInfo.name.toString() == "escaped-correlation" }.correlationValuesJson
        assertEquals("""{"key":"\u001b"}""", expected)
        assertEquals(expected, actual)
    }
}
