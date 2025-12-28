// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.common.json.LemlineJson
import io.serverlessworkflow.api.types.EventData
import io.serverlessworkflow.api.types.EventDataschema
import io.serverlessworkflow.api.types.EventSource
import io.serverlessworkflow.api.types.EventTime
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.util.*

/**
 * Resolve source value - can be URI template or expression.
 * Expressions are stored as-is for later evaluation against event.source.
 */
fun resolveSourceValue(source: EventSource?): String? {
    if (source == null) return null
    return when (val value = source.get()) {
        is UriTemplate -> when (val uri = value.get()) {
            is URI -> uri.toString()
            is String -> uri
            else -> error("Unsupported UriTemplate value: ${uri?.javaClass?.name}")
        }

        is String -> value
        else -> error("Unsupported EventSource type: ${value?.javaClass?.name}")
    }
}

/**
 * Resolve dataschema value - can be URI or expression.
 */
fun resolveDataschemaValue(dataschema: EventDataschema?): String? {
    if (dataschema == null) return null
    return when (val value = dataschema.get()) {
        is UriTemplate -> when (val uri = value.get()) {
            is URI -> uri.toString()
            is String -> uri
            else -> error("Unsupported UriTemplate value: ${uri?.javaClass?.name}")
        }

        is String -> value
        else -> error("Unsupported EventDataschema type: ${value?.javaClass?.name}")
    }
}

/**
 * Resolve time value - can be datetime or expression.
 */
fun resolveTimeValue(time: EventTime?): String? {
    if (time == null) return null
    return when (val value = time.get()) {
        is Date -> value.toInstant().toString()
        is String -> value
        else -> error("Unsupported EventTime type: ${value?.javaClass?.name}")
    }
}

/**
 * Resolve data filter value - can be literal object or expression.
 * If it's an expression, store it for evaluation at event arrival.
 * If it's a literal, convert to JSON string for comparison.
 */
fun resolveDataFilterValue(data: EventData?): String? {
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
