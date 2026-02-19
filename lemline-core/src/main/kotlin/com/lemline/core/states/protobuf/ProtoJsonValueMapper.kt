// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal fun JsonElement.toProtoJsonValue(): Value =
    when (this) {
        JsonNull -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
        is JsonObject -> Value.newBuilder().setStructValue(toProtoJsonStruct()).build()
        is JsonArray -> Value.newBuilder().setListValue(toProtoJsonListValue()).build()
        is JsonPrimitive -> toProtoJsonValue()
    }

internal fun JsonObject.toProtoJsonStruct(): Struct =
    Struct.newBuilder()
        .putAllFields(mapValues { (_, value) -> value.toProtoJsonValue() })
        .build()

internal fun List<JsonElement>.toProtoJsonListValue(): ListValue =
    ListValue.newBuilder()
        .addAllValues(map { it.toProtoJsonValue() })
        .build()

internal fun JsonArray.toProtoJsonListValue(): ListValue = this.toList().toProtoJsonListValue()

internal fun Value.toKotlinJsonElement(): JsonElement = when (kindCase) {
    Value.KindCase.NULL_VALUE -> JsonNull
    Value.KindCase.NUMBER_VALUE -> numberValue.toJsonPrimitivePreferIntegralNumber()
    Value.KindCase.STRING_VALUE -> JsonPrimitive(stringValue)
    Value.KindCase.BOOL_VALUE -> JsonPrimitive(boolValue)
    Value.KindCase.STRUCT_VALUE -> structValue.toKotlinJsonObject()
    Value.KindCase.LIST_VALUE -> JsonArray(listValue.valuesList.map { it.toKotlinJsonElement() })
    Value.KindCase.KIND_NOT_SET -> JsonNull
    null -> JsonNull
}

internal fun Struct.toKotlinJsonObject(): JsonObject =
    JsonObject(fieldsMap.mapValues { (_, value) -> value.toKotlinJsonElement() })

internal fun ListValue.toKotlinJsonElementList(): List<JsonElement> =
    valuesList.map { it.toKotlinJsonElement() }

private fun JsonPrimitive.toProtoJsonValue(): Value {
    if (isString) {
        return Value.newBuilder().setStringValue(content).build()
    }
    booleanOrNull?.let { return Value.newBuilder().setBoolValue(it).build() }
    doubleOrNull?.let { return Value.newBuilder().setNumberValue(it).build() }
    return Value.newBuilder().setStringValue(content).build()
}

private fun Double.toJsonPrimitivePreferIntegralNumber(): JsonPrimitive {
    if (isFinite() && this >= Long.MIN_VALUE.toDouble() && this <= Long.MAX_VALUE.toDouble()) {
        val longValue = toLong()
        if (this == longValue.toDouble()) {
            return JsonPrimitive(longValue)
        }
    }
    return JsonPrimitive(this)
}
