// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.cloudevents.CloudEventMatcher
import com.lemline.core.cloudevents.CloudEventParser.parseData
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.utils.convertFilter
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.EventConsumptionStrategy
import io.serverlessworkflow.api.types.EventFilter as SdkEventFilter
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parsed definition filter, shared by listen-task routing and schedule.on routing/collection. */
data class CachedEventFilter(val standard: EventFilter, val extensions: Map<String, JsonElement>) {
    fun matches(event: CloudEvent, data: () -> JsonElement = { event.parseData() }): Boolean =
        matchesExtensions(event) && CloudEventMatcher.matchesFilters(event, listOf(standard), data)

    fun matches(event: CloudEvent, correlations: MutableMap<String, String>, context: JsonElement? = JsonObject(emptyMap())): Boolean =
        matchesExtensions(event) && CloudEventMatcher.matchesFilters(
            event, listOf(standard), context, correlations
        )

    private fun matchesExtensions(event: CloudEvent): Boolean = extensions.all { (name, expected) ->
        val actual = event.getExtension(name) ?: return@all false
        val actualJson = with(LemlineJson) { jacksonMapper.valueToTree<JsonNode>(actual).toJsonElement() }
        if (expected is JsonPrimitive && expected.isString && ExpressionUtils.isExpr(expected.content)) {
            CloudEventMatcher.evaluateExpressionAsBoolean(expected.content, actualJson)
        } else actualJson == expected
    }

    companion object {
        fun from(filter: SdkEventFilter) = CachedEventFilter(
            convertFilter(filter),
            filter.with?.additionalProperties.orEmpty().mapValues { (_, value) ->
                with(LemlineJson) { jacksonMapper.valueToTree<JsonNode>(value).toJsonElement() }
            },
        )
    }
}

data class CachedEventConsumption(
    val strategy: ListenStrategy,
    val filters: List<CachedEventFilter>,
    val untilExpression: String? = null,
    val untilEvents: CachedEventConsumption? = null,
) {
    val wildcard: Boolean get() = strategy == ListenStrategy.ANY && filters.isEmpty()

    /** Definition-level preselection, including every nested termination filter. */
    fun mayMatch(event: CloudEvent, data: () -> JsonElement = { event.parseData() }): Boolean =
        wildcard || filters.any { it.matches(event, data) } || untilEvents?.mayMatch(event, data) == true

    fun allFilters(): List<CachedEventFilter> = filters + untilEvents?.allFilters().orEmpty()

    companion object {
        fun from(strategy: Any): CachedEventConsumption = when (strategy) {
            is OneEventConsumptionStrategy -> CachedEventConsumption(
                ListenStrategy.ONE, listOf(CachedEventFilter.from(strategy.one))
            )
            is AllEventConsumptionStrategy -> CachedEventConsumption(
                ListenStrategy.ALL, strategy.all.map(CachedEventFilter::from)
            )
            is AnyEventConsumptionStrategy -> {
                val until = strategy.until?.get()
                CachedEventConsumption(
                    ListenStrategy.ANY, strategy.any.map(CachedEventFilter::from),
                    untilExpression = until as? String,
                    untilEvents = (until as? EventConsumptionStrategy)?.get()?.let(::from),
                )
            }
            else -> error("Unknown event consumption strategy: ${strategy.javaClass.name}")
        }
    }
}

data class CachedEventSchedule(val workflowInfo: WorkflowInfo, val consumption: CachedEventConsumption)
