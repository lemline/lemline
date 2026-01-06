// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.EventFilter
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * Utility functions for CloudEvent handling in the FullOrchestrator.
 *
 * This object provides:
 * - CloudEvent building from EmitConfig
 * - CloudEvent filtering and matching
 * - CloudEvent data extraction and transformation
 */
object CloudEventUtils {

    private val logger = logger()

    /**
     * Build a CloudEvent from EmitConfig.
     */
    fun buildCloudEvent(config: EmitConfig): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(config.id)
            .withSource(URI.create(config.source))
            .withType(config.type)

        config.time?.let { builder.withTime(OffsetDateTime.parse(it)) }
        config.subject?.let { builder.withSubject(it) }
        config.dataschema?.let { builder.withDataSchema(URI.create(it)) }
        config.datacontenttype?.let { builder.withDataContentType(it) }

        config.data?.let { data ->
            val contentType = config.datacontenttype ?: "application/json"
            builder.withDataContentType(contentType)
            builder.withData(contentType, data.toString().toByteArray())
        }

        config.extensions?.forEach { (key, value) ->
            builder.withExtension(key, value)
        }

        return builder.build()
    }

    /**
     * Check if a CloudEvent matches any of the given filters.
     *
     * Supports all CloudEvent filter properties:
     * - Literal-only fields (exact match): type, id, subject, datacontenttype
     * - Expression-capable fields: source, dataschema, time, data (dataFilter)
     *
     * Note: This overload doesn't support correlation matching. Use the overload
     * with correlationContext for correlation-aware filtering.
     */
    fun matchesFilters(event: CloudEvent, filters: List<EventFilter>): Boolean {
        return matchesFilters(event, filters, correlationContext = null, establishedCorrelations = null)
    }

    /**
     * Check if a CloudEvent matches any of the given filters with correlation support.
     *
     * @param event The CloudEvent to check
     * @param filters The list of filters to match against
     * @param correlationContext Workflow context for evaluating `correlate.expect` expressions (e.g., $input, $context)
     * @param establishedCorrelations Mutable map tracking established correlation values for Mode 2 (auto-correlation).
     *        When a filter with correlations but no `expect` matches, its extracted values are stored here.
     *        Subsequent filters with the same correlation keys must match the established values.
     */
    fun matchesFilters(
        event: CloudEvent,
        filters: List<EventFilter>,
        correlationContext: JsonElement?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean {
        if (filters.isEmpty()) return true // Empty filters = wildcard

        // Parse event data once (lazily) for data filter evaluation
        val eventData by lazy { parseEventData(event) }

        return filters.any { filter ->
            // Literal-only fields: exact string match
            if (!matchesLiteralField(filter.type, event.type)) return@any false
            if (!matchesLiteralField(filter.id, event.id)) return@any false
            if (!matchesLiteralField(filter.subject, event.subject)) return@any false
            if (!matchesLiteralField(filter.datacontenttype, event.dataContentType)) return@any false

            // Expression-capable fields
            if (!matchesExprField(filter.source, event.source?.toString())) return@any false
            if (!matchesExprField(filter.dataschema, event.dataSchema?.toString())) return@any false
            if (!matchesTimeField(filter.time, event.time)) return@any false

            // Data filter (expression against event payload)
            if (!matchesDataFilter(filter.dataFilter, eventData)) return@any false

            // Correlation filter
            if (!matchesCorrelations(filter.correlations, eventData, correlationContext, establishedCorrelations)) {
                return@any false
            }

            true
        }
    }

    /**
     * Check if event data matches correlation requirements.
     *
     * For each correlation definition:
     * - Mode 1 (expect present): Extract value from event using `from`, evaluate `expect` against context, compare
     * - Mode 2 (expect absent): Extract value from event using `from`, compare against established value if exists,
     *   otherwise establish the value for future matches
     *
     * @return true if all correlations match (or no correlations defined)
     */
    private fun matchesCorrelations(
        correlations: Map<String, CorrelationDef>?,
        eventData: JsonElement,
        correlationContext: JsonElement?,
        establishedCorrelations: MutableMap<String, String>?
    ): Boolean {
        if (correlations.isNullOrEmpty()) return true

        // Track values to establish after successful match (for Mode 2)
        val valuesToEstablish = mutableMapOf<String, String>()

        for ((key, def) in correlations) {
            val eventValue = evaluateFromExpression(def.from, eventData)
            if (eventValue == null) {
                logger.debug { "Correlation '$key': failed to extract value from event using '${def.from}'" }
                return false
            }

            when {
                def.expect != null -> {
                    if (correlationContext == null) {
                        logger.warn { "Correlation '$key' has expect but no correlationContext provided" }
                        return false
                    }
                    val expectedValue = evaluateExpectExpression(def.expect, correlationContext)
                    if (expectedValue == null) {
                        logger.debug { "Correlation '$key': failed to evaluate expect '${def.expect}'" }
                        return false
                    }
                    if (eventValue != expectedValue) {
                        logger.debug { "Correlation '$key': value mismatch - event='$eventValue', expected='$expectedValue'" }
                        return false
                    }
                }

                else -> {
                    val established = establishedCorrelations?.get(key)
                    if (established != null) {
                        if (eventValue != established) {
                            logger.debug { "Correlation '$key': value mismatch - event='$eventValue', established='$established'" }
                            return false
                        }
                    } else {
                        valuesToEstablish[key] = eventValue
                    }
                }
            }
        }

        // All correlations matched - establish Mode 2 values
        if (establishedCorrelations != null) {
            establishedCorrelations.putAll(valuesToEstablish)
            if (valuesToEstablish.isNotEmpty()) {
                logger.debug { "Established correlations: $valuesToEstablish" }
            }
        }

        return true
    }

    /**
     * Evaluate a value which can be either a literal or a JQ expression.
     * For `from` expressions: evaluates against input data (event payload).
     */
    private fun evaluateFromExpression(expression: String, eventData: JsonElement): String? {
        return if (ExpressionUtils.isExpr(expression)) {
            try {
                val trimmedExpr = ExpressionUtils.trimExpr(expression)
                val result = with(LemlineJson) {
                    val inputNode = eventData.toJsonNode()
                    val scope = LemlineJson.jacksonMapper.createObjectNode()
                    JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
                }
                when (result) {
                    is JsonPrimitive -> result.content
                    else -> result.toString()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate from expression: $expression" }
                null
            }
        } else {
            expression
        }
    }

    /**
     * Evaluate an `expect` expression against workflow scope.
     * The scope contains `$context`, `$input`, `$workflow`, etc.
     */
    private fun evaluateExpectExpression(expression: String, scopeContext: JsonElement): String? {
        return if (ExpressionUtils.isExpr(expression)) {
            try {
                val trimmedExpr = ExpressionUtils.trimExpr(expression)
                val result = with(LemlineJson) {
                    val inputNode = LemlineJson.jacksonMapper.createObjectNode()
                    val scopeNode = scopeContext.toJsonNode() as com.fasterxml.jackson.databind.node.ObjectNode
                    JQExpression.eval(inputNode, trimmedExpr, scopeNode).toJsonElement()
                }
                when (result) {
                    is JsonPrimitive -> result.content
                    else -> result.toString()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate expect expression: $expression" }
                null
            }
        } else {
            expression
        }
    }

    /**
     * Matches a literal-only field (exact string match).
     */
    private fun matchesLiteralField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true
        return filterValue == eventValue
    }

    /**
     * Matches an expression-capable field.
     * If the filter value is an expression (starts with ${), evaluate it against the event value.
     * Otherwise, do exact string match.
     */
    private fun matchesExprField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateExpressionAsBoolean(filterValue, eventValue?.let { JsonPrimitive(it) } ?: JsonNull)
        } else {
            filterValue == eventValue
        }
    }

    private fun matchesTimeField(filterValue: String?, eventTime: OffsetDateTime?): Boolean {
        if (filterValue == null) return true
        if (eventTime == null) return false

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateExpressionAsBoolean(filterValue, JsonPrimitive(eventTime.toString()))
        } else {
            compareTimestampsNormalized(filterValue, eventTime)
        }
    }

    private fun compareTimestampsNormalized(filterValue: String, eventTime: OffsetDateTime): Boolean {
        return try {
            val filterTime = OffsetDateTime.parse(filterValue)
            filterTime.isEqual(eventTime)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse filter time value as OffsetDateTime: $filterValue" }
            filterValue == eventTime.toString()
        }
    }

    /**
     * Matches data filter expression against event payload.
     * The filter expression is evaluated against the event data and must return boolean.
     */
    private fun matchesDataFilter(dataFilter: String?, eventData: JsonElement): Boolean {
        if (dataFilter == null) return true
        if (eventData == JsonNull) return false

        return evaluateExpressionAsBoolean("\${$dataFilter}", eventData)
    }

    /**
     * Evaluates a JQ expression against input and expects a boolean result.
     */
    fun evaluateExpressionAsBoolean(expression: String, input: JsonElement): Boolean {
        return try {
            val trimmedExpr = ExpressionUtils.trimExpr(expression)
            val result = with(LemlineJson) {
                val inputNode = input.toJsonNode()
                // There is no scope here, as we evaluate against Cloud Event data only.
                val scope = LemlineJson.jacksonMapper.createObjectNode()
                JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
            }
            (result as? JsonPrimitive)?.booleanOrNull == true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate expression: $expression" }
            false
        }
    }

    /**
     * Parses the CloudEvent data payload to JsonElement.
     */
    private fun parseEventData(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull
        return try {
            Json.parseToJsonElement(String(data.toBytes()))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse CloudEvent data as JSON" }
            JsonNull
        }
    }

    /**
     * Convert a CloudEvent to JsonElement based on the readAs mode.
     *
     * @param readAs How to extract event content (DATA, ENVELOPE, RAW)
     * @return The extracted content as JsonElement
     */
    fun CloudEvent.toJsonElement(readAs: ListenTaskConfiguration.ListenAndReadAs): JsonElement {
        return when (readAs) {
            ListenTaskConfiguration.ListenAndReadAs.DATA -> {
                // Extract just the data payload
                data?.let {
                    val dataString = String(it.toBytes())
                    try {
                        Json.parseToJsonElement(dataString)
                    } catch (_: Exception) {
                        JsonPrimitive(dataString)
                    }
                } ?: JsonNull
            }

            ListenTaskConfiguration.ListenAndReadAs.ENVELOPE -> {
                // Return the full CloudEvent structure
                buildJsonObject {
                    put("specversion", JsonPrimitive(specVersion.toString()))
                    put("id", JsonPrimitive(id))
                    put("source", JsonPrimitive(source.toString()))
                    put("type", JsonPrimitive(type))
                    time?.let { put("time", JsonPrimitive(it.toString())) }
                    subject?.let { put("subject", JsonPrimitive(it)) }
                    dataSchema?.let { put("dataschema", JsonPrimitive(it.toString())) }
                    dataContentType?.let { put("datacontenttype", JsonPrimitive(it)) }
                    data?.let {
                        val dataString = String(it.toBytes())
                        try {
                            put("data", Json.parseToJsonElement(dataString))
                        } catch (_: Exception) {
                            put("data", JsonPrimitive(dataString))
                        }
                    }
                }
            }

            ListenTaskConfiguration.ListenAndReadAs.RAW -> {
                // Return raw bytes as string
                data?.let { JsonPrimitive(String(it.toBytes())) } ?: JsonNull
            }
        }
    }
}
