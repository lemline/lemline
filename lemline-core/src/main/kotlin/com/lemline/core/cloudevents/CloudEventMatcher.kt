// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.cloudevents.CloudEventParser.parseData
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.utils.compareTimestampsNormalized
import io.cloudevents.CloudEvent
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object CloudEventMatcher {

    private val logger = logger()

    fun matchesFilters(
        event: CloudEvent,
        filters: List<EventFilter>,
        eventDataProvider: () -> JsonElement = { event.parseData() }
    ): Boolean = matchesFiltersInternal(event, filters, eventDataProvider, null, null)

    fun matchesFilters(
        event: CloudEvent,
        filters: List<EventFilter>,
        correlationContext: JsonElement?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean = matchesFiltersInternal(
        event, filters, { event.parseData() }, correlationContext, establishedCorrelations
    )

    private fun matchesFiltersInternal(
        event: CloudEvent,
        filters: List<EventFilter>,
        eventDataProvider: () -> JsonElement,
        correlationContext: JsonElement?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean {
        if (filters.isEmpty()) return true

        val eventData by lazy { eventDataProvider() }

        return filters.any { filter ->
            matchesLiteralField(filter.type, event.type) &&
                matchesLiteralField(filter.id, event.id) &&
                matchesLiteralField(filter.subject, event.subject) &&
                matchesLiteralField(filter.datacontenttype, event.dataContentType) &&
                matchesExprField(filter.source, event.source?.toString()) &&
                matchesExprField(filter.dataschema, event.dataSchema?.toString()) &&
                matchesTimeField(filter.time, event.time) &&
                matchesDataFilter(filter.dataFilter, eventData) &&
                matchesCorrelations(filter.correlations, eventData, correlationContext, establishedCorrelations)
        }
    }

    private fun matchesCorrelations(
        correlations: Map<String, CorrelationDef>?,
        eventData: JsonElement,
        correlationContext: JsonElement?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean {
        if (correlations.isNullOrEmpty()) return true
        if (correlationContext == null && establishedCorrelations == null) return true

        val valuesToEstablish = mutableMapOf<String, String>()

        for ((key, def) in correlations) {
            val eventValue = evaluateFromExpression(def.from, eventData) ?: run {
                logger.debug { "Correlation '$key': failed to extract value from event using '${def.from}'" }
                return false
            }

            val matchResult = when (val expect = def.expect) {
                null -> matchAutoCorrelation(key, eventValue, establishedCorrelations, valuesToEstablish)
                else -> matchExpectCorrelation(key, expect, eventValue, correlationContext)
            }
            if (!matchResult) return false
        }

        establishedCorrelations?.let { established ->
            established.putAll(valuesToEstablish)
            if (valuesToEstablish.isNotEmpty()) {
                logger.debug { "Established correlations: $valuesToEstablish" }
            }
        }

        return true
    }

    private fun matchExpectCorrelation(
        key: String,
        expect: String,
        eventValue: String,
        correlationContext: JsonElement?
    ): Boolean {
        if (correlationContext == null) {
            logger.warn { "Correlation '$key' has expect but no correlationContext provided" }
            return false
        }
        val expectedValue = evaluateExpectExpression(expect, correlationContext) ?: run {
            logger.debug { "Correlation '$key': failed to evaluate expect '$expect'" }
            return false
        }
        return (eventValue == expectedValue).also { matched ->
            if (!matched) logger.debug { "Correlation '$key': value mismatch - event='$eventValue', expected='$expectedValue'" }
        }
    }

    private fun matchAutoCorrelation(
        key: String,
        eventValue: String,
        establishedCorrelations: MutableMap<String, String>?,
        valuesToEstablish: MutableMap<String, String>
    ): Boolean {
        val established = establishedCorrelations?.get(key)
        return when {
            established == null -> {
                valuesToEstablish[key] = eventValue
                true
            }

            eventValue == established -> true
            else -> {
                logger.debug { "Correlation '$key': value mismatch - event='$eventValue', established='$established'" }
                false
            }
        }
    }

    fun evaluateFromExpression(expression: String, eventData: JsonElement): String? =
        runCatching {
            val jqExpr = if (ExpressionUtils.isExpr(expression)) {
                ExpressionUtils.trimExpr(expression)
            } else {
                expression
            }
            evaluateJQ(jqExpr, eventData).toStringOrNull()
        }.onFailure { e ->
            logger.warn(e) { "Failed to evaluate from expression: $expression" }
        }.getOrNull()

    fun extractCorrelationValues(filter: EventFilter, eventData: JsonElement): Map<String, String>? =
        filter.correlations
            ?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { (key, def) ->
                evaluateFromExpression(def.from, eventData)?.let { key to it }
            }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }

    private fun evaluateExpectExpression(expression: String, scopeContext: JsonElement): String? =
        if (ExpressionUtils.isExpr(expression)) {
            runCatching {
                val trimmedExpr = ExpressionUtils.trimExpr(expression)
                with(LemlineJson) {
                    val inputNode = LemlineJson.jacksonMapper.createObjectNode()
                    val scopeNode = scopeContext.toJsonNode() as com.fasterxml.jackson.databind.node.ObjectNode
                    JQExpression.eval(inputNode, trimmedExpr, scopeNode).toJsonElement()
                }.toStringValue()
            }.onFailure { e ->
                logger.warn(e) { "Failed to evaluate expect expression: $expression" }
            }.getOrNull()
        } else {
            expression
        }

    fun matchesLiteralField(filterValue: String?, eventValue: String?): Boolean =
        filterValue == null || filterValue == eventValue

    fun matchesExprField(filterValue: String?, eventValue: String?): Boolean = when {
        filterValue == null -> true
        eventValue == null -> false
        ExpressionUtils.isExpr(filterValue) -> evaluateExpressionAsBoolean(filterValue, JsonPrimitive(eventValue))
        else -> filterValue == eventValue
    }

    fun matchesTimeField(filterValue: String?, eventTime: OffsetDateTime?): Boolean = when {
        filterValue == null -> true
        eventTime == null -> false
        ExpressionUtils.isExpr(filterValue) -> evaluateExpressionAsBoolean(
            filterValue,
            JsonPrimitive(eventTime.toString())
        )

        else -> compareTimestampsNormalized(filterValue, eventTime)
    }

    private fun matchesDataFilter(dataFilter: String?, eventData: JsonElement): Boolean {
        if (dataFilter == null) return true
        if (eventData == JsonNull) return false

        val trimmed = dataFilter.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { Json.parseToJsonElement(dataFilter) }
                .onFailure { e -> logger.warn(e) { "Failed to parse data filter as JSON literal: $dataFilter" } }
                .map { eventData == it }
                .getOrDefault(false)
        } else {
            evaluateExpressionAsBoolean("\${$dataFilter}", eventData)
        }
    }

    fun evaluateExpressionAsBoolean(expression: String, input: JsonElement): Boolean {
        val trimmedExpr = ExpressionUtils.trimExpr(expression)
        val result = evaluateJQOrNull(trimmedExpr, input) ?: return false
        return (result as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun evaluateJQ(jqExpression: String, input: JsonElement): JsonElement =
        with(LemlineJson) {
            val inputNode = input.toJsonNode()
            val scope = LemlineJson.jacksonMapper.createObjectNode()
            JQExpression.eval(inputNode, jqExpression, scope).toJsonElement()
        }

    private fun evaluateJQOrNull(jqExpression: String, input: JsonElement): JsonElement? =
        with(LemlineJson) {
            val inputNode = input.toJsonNode()
            val scope = LemlineJson.jacksonMapper.createObjectNode()
            JQExpression.evalOrNull(inputNode, jqExpression, scope)?.toJsonElement()
        }

    private fun JsonElement.toStringOrNull(): String? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun JsonElement.toStringValue(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }
}
