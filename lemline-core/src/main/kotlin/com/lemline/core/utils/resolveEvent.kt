// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.common.json.LemlineJson
import com.lemline.core.processors.NodeProcessor
import com.lemline.core.processors.scope.Scope
import io.serverlessworkflow.api.types.EventData
import io.serverlessworkflow.api.types.EventDataschema
import io.serverlessworkflow.api.types.EventSource
import io.serverlessworkflow.api.types.EventTime
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
fun NodeProcessor<*, *>.resolveSourceValue(
    source: EventSource?,
    transformedInput: JsonElement,
    scope: Scope
): String? {
    if (source == null) return null
    return when (val value = source.get()) {
        is UriTemplate -> when (val uri = value.get()) {
            is URI -> uri.toString()
            is String -> evalString(transformedInput, uri, "source", scope)
            else -> null
        }

        is String -> value
        else -> null
    }
}

/**
 * Resolve dataschema value - can be URI or expression.
 */
@ExperimentalTime
fun NodeProcessor<*, *>.resolveDataschemaValue(
    dataschema: EventDataschema?,
    transformedInput: JsonElement,
    scope: Scope
): String? {
    if (dataschema == null) return null
    return when (val schema = dataschema.get()) {
        is URI -> schema.toString()
        is String -> evalString(transformedInput, schema, "schema", scope)
        else -> null
    }
}

/**
 * Resolve time value - can be datetime or expression.
 * Converts Date to ISO-8601 format (RFC 3339) for CloudEvent compatibility.
 */
@ExperimentalTime
fun NodeProcessor<*, *>.resolveTimeValue(
    time: EventTime?,
    transformedInput: JsonElement,
    scope: Scope
): String? {
    if (time == null) return null
    return when (val date = time.get()) {
        is Date -> date.toInstant().toString()
        is String -> evalString(transformedInput, date, "time", scope)
        else -> null
    }
}

/**
 * Resolve data filter value - can be literal object or expression.
 * If it's an expression, store it for evaluation at event arrival.
 * If it's a literal, convert to JSON string for comparison.
 */
@ExperimentalTime
fun NodeProcessor<*, *>.resolveDataFilterValue(
    data: EventData?,
    transformedInput: JsonElement,
    scope: Scope
): String? {
    if (data == null) return null
    return when (val value = data.get()) {
        is String -> {
            // Runtime expression - store as-is
            if (ExpressionUtils.isExpr(value)) {
                ExpressionUtils.trimExpr(value)
            } else {
                value
            }
        }

        else -> {
            // Literal object - convert to JSON string
            if (value != null) {
                with(LemlineJson) { value.toJsonElement().toString() }
            } else {
                null
            }
        }
    }
}
