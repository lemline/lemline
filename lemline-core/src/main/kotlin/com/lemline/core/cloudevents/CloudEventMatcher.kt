// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
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

object CloudEventMatcher {

    private val logger = logger()

    fun matchesFilters(
        event: CloudEvent,
        filters: List<EventFilter>,
        eventDataProvider: () -> JsonNode = { event.parseData() }
    ): Boolean = matchesFiltersInternal(event, filters, eventDataProvider, null, null)

    fun matchesFilters(
        event: CloudEvent,
        filters: List<EventFilter>,
        correlationContext: JsonNode?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean = matchesFiltersInternal(
        event, filters, { event.parseData() }, correlationContext, establishedCorrelations
    )

    private fun matchesFiltersInternal(
        event: CloudEvent,
        filters: List<EventFilter>,
        eventDataProvider: () -> JsonNode,
        correlationContext: JsonNode?,
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
        eventData: JsonNode,
        correlationContext: JsonNode?,
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
        correlationContext: JsonNode?
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

    fun evaluateFromExpression(expression: String, eventData: JsonNode): String? =
        runCatching {
            val jqExpr = if (ExpressionUtils.isExpr(expression)) {
                ExpressionUtils.trimExpr(expression)
            } else {
                expression
            }
            evaluateJQ(jqExpr, eventData.toLegacyCorrelationNode()).toStringOrNull()
        }.onFailure { e ->
            logger.warn(e) { "Failed to evaluate from expression: $expression" }
        }.getOrNull()

    fun extractCorrelationValues(filter: EventFilter, eventData: JsonNode): Map<String, String>? =
        filter.correlations
            ?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { (key, def) ->
                evaluateFromExpression(def.from, eventData)?.let { key to it }
            }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Evaluates a `correlate.expect` runtime expression.
     *
     * The workflow data is exposed as the expression arguments (`$input`, `$context`,
     * `$workflow`, `$task`), as for any other runtime expression. The expression has no input.
     *
     * @throws IllegalArgumentException if the expression cannot be evaluated
     */
    fun evaluateExpect(expression: String, correlationContext: JsonNode): JsonNode =
        JQExpression.eval(
            LemlineJson.jacksonMapper.createObjectNode(),
            ExpressionUtils.trimExpr(expression),
            correlationContext.toLegacyCorrelationNode() as ObjectNode,
        )

    private fun evaluateExpectExpression(expression: String, scopeContext: JsonNode): String? =
        if (ExpressionUtils.isExpr(expression)) {
            runCatching {
                evaluateExpect(expression, scopeContext).toStringValue()
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
        ExpressionUtils.isExpr(filterValue) -> evaluateExpressionAsBoolean(filterValue, jsonValue(eventValue))
        else -> filterValue == eventValue
    }

    fun matchesTimeField(filterValue: String?, eventTime: OffsetDateTime?): Boolean = when {
        filterValue == null -> true
        eventTime == null -> false
        ExpressionUtils.isExpr(filterValue) -> evaluateExpressionAsBoolean(
            filterValue,
            jsonValue(eventTime.toString())
        )

        else -> compareTimestampsNormalized(filterValue, eventTime)
    }

    private fun matchesDataFilter(dataFilter: String?, eventData: JsonNode): Boolean {
        if (dataFilter == null) return true
        if (eventData == NullNode.instance) return false

        val trimmed = dataFilter.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { parseJson(dataFilter) }
                .onFailure { e -> logger.warn(e) { "Failed to parse data filter as JSON literal: $dataFilter" } }
                .map { eventData == it }
                .getOrDefault(false)
        } else {
            evaluateExpressionAsBoolean("\${$dataFilter}", eventData)
        }
    }

    fun evaluateExpressionAsBoolean(expression: String, input: JsonNode): Boolean {
        val trimmedExpr = ExpressionUtils.trimExpr(expression)
        val result = evaluateJQOrNull(trimmedExpr, input) ?: return false
        return (result as? ValueNode)?.booleanOrNull == true
    }

    private fun evaluateJQ(jqExpression: String, input: JsonNode): JsonNode =
        with(LemlineJson) {
            val inputNode = input
            val scope = LemlineJson.jacksonMapper.createObjectNode()
            JQExpression.eval(inputNode, jqExpression, scope)
        }

    private fun evaluateJQOrNull(jqExpression: String, input: JsonNode): JsonNode? =
        with(LemlineJson) {
            val inputNode = input
            val scope = LemlineJson.jacksonMapper.createObjectNode()
            JQExpression.evalOrNull(inputNode, jqExpression, scope)
        }

    private fun JsonNode.toStringOrNull(): String? = toCorrelationValue()

    private fun JsonNode.toStringValue(): String = toCorrelationValue() ?: "null"
}
