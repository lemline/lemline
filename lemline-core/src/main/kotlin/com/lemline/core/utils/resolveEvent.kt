// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.common.json.LemlineJson
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import io.serverlessworkflow.api.types.EventData
import io.serverlessworkflow.api.types.EventDataschema
import io.serverlessworkflow.api.types.EventSource
import io.serverlessworkflow.api.types.EventTime
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.api.types.EventFilter as SdkEventFilter
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.time.OffsetDateTime
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
 * Compare a filter time value against a CloudEvent time using normalized temporal comparison.
 *
 * This handles the case where string representations may differ but represent the same instant
 * (e.g., "2024-06-15T14:30:00Z" vs "2024-06-15T14:30Z").
 *
 * @param filterValue The filter time value (literal string, not an expression)
 * @param eventTime The CloudEvent's time attribute
 * @return true if times are equal, false otherwise
 */
fun compareTimestampsNormalized(filterValue: String, eventTime: OffsetDateTime): Boolean {
    return try {
        val filterTime = OffsetDateTime.parse(filterValue)
        filterTime.isEqual(eventTime)
    } catch (_: Exception) {
        filterValue == eventTime.toString()
    }
}

fun resolveDataFilterValue(data: EventData?): String? {
    if (data == null) return null
    return when (val value = data.get()) {
        is String -> {
            if (ExpressionUtils.isExpr(value)) {
                ExpressionUtils.trimExpr(value)
            } else {
                value
            }
        }

        else -> {
            if (value != null) {
                with(LemlineJson) { value.toJsonElement().toString() }
            } else {
                null
            }
        }
    }
}

fun convertFilter(sdkFilter: SdkEventFilter): EventFilter {
    val eventProps = sdkFilter.with
    return EventFilter(
        type = eventProps?.type,
        source = resolveSourceValue(eventProps?.source),
        subject = eventProps?.subject,
        id = eventProps?.id,
        datacontenttype = eventProps?.datacontenttype,
        dataschema = resolveDataschemaValue(eventProps?.dataschema),
        time = resolveTimeValue(eventProps?.time),
        dataFilter = resolveDataFilterValue(eventProps?.data),
        correlations = convertCorrelations(sdkFilter)
    )
}

private fun convertCorrelations(sdkFilter: SdkEventFilter): Map<String, CorrelationDef>? {
    val correlate = sdkFilter.correlate?.additionalProperties
    if (correlate.isNullOrEmpty()) return null

    return correlate.mapValues { (_, prop) ->
        CorrelationDef(
            from = prop.from ?: error("Correlation missing 'from' expression"),
            expect = prop.expect
        )
    }
}
