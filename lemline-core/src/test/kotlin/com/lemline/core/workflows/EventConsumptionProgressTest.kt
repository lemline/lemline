// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenStrategy
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.matchers.shouldBe
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class EventConsumptionProgressTest {
    private fun event(type: String, key: String = "x", tenant: String = "acme") = CloudEventBuilder.v1()
        .withId("$type-$key-$tenant").withSource(URI("https://tests")).withType(type)
        .withExtension("tenant", tenant).withData("application/json", "{\"key\":\"$key\"}".toByteArray()).build()

    @Test fun `all correlation and extensions survive serialization and reject mismatched events`() {
        val filters = listOf("first", "second").map { type -> CachedEventFilter(
            EventFilter(type = type, correlations = mapOf("key" to CorrelationDef("\${ .key }"))),
            mapOf("tenant" to JsonPrimitive("acme")),
        ) }
        val rule = CachedEventConsumption(ListenStrategy.ALL, filters)
        val state = EventConsumptionProgress()
        state.consume(rule, event("first", tenant = "other"), null) shouldBe false
        state.matched shouldBe emptySet()
        state.consume(rule, event("first"), null) shouldBe false
        val restored = Json.decodeFromString<EventConsumptionProgress>(Json.encodeToString(state))
        restored.consume(rule, event("second", key = "y"), null) shouldBe false
        restored.consume(rule, event("second"), null) shouldBe true
    }

    @Test fun `until expect uses the instance context`() {
        val rule = CachedEventConsumption(ListenStrategy.ONE, listOf(CachedEventFilter(
            EventFilter(type = "stop", correlations = mapOf("key" to CorrelationDef("\${ .key }", "\${ \$expected }"))), emptyMap()
        )))
        val context = Json.parseToJsonElement("{\"expected\":\"x\"}")
        val state = EventConsumptionProgress()
        state.consume(rule, event("stop", "y"), context) shouldBe false
        state.consume(rule, event("stop", "x"), context) shouldBe true
    }

    @Test fun `one event can satisfy overlapping all filters`() {
        val rule = CachedEventConsumption(ListenStrategy.ALL, listOf(
            CachedEventFilter(EventFilter(type = "stop"), emptyMap()),
            CachedEventFilter(EventFilter(source = "https://tests"), emptyMap()),
        ))
        EventConsumptionProgress().consume(rule, event("stop"), null) shouldBe true
    }
}
