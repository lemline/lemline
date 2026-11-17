// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*

/** Runtime trees are treated as values: builders and merges never mutate their inputs. */
private val treeReader = com.fasterxml.jackson.databind.ObjectMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS, com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
fun parseJson(text: String): JsonNode = requireNotNull(treeReader.readTree(text)) { "Empty JSON document" }.also { require(!it.isMissingNode) { "Empty JSON document" }; validateDecimalBounds(it) }
fun jsonValue(value: String?): JsonNode = value?.let { TextNode.valueOf(it) } ?: NullNode.instance
fun jsonValue(value: Boolean?): JsonNode = value?.let { BooleanNode.valueOf(it) } ?: NullNode.instance
fun jsonValue(value: Number?): JsonNode = when (value) {
    null -> NullNode.instance
    is Byte, is Short, is Int -> IntNode.valueOf(value.toInt())
    is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) IntNode.valueOf(value.toInt()) else LongNode.valueOf(value)
    is Float -> if (value.isFinite()) DecimalNode.valueOf(value.toString().toBigDecimal()) else DoubleNode.valueOf(value.toDouble())
    is Double -> if (value.isFinite()) DecimalNode.valueOf(java.math.BigDecimal.valueOf(value)) else DoubleNode.valueOf(value)
    is java.math.BigInteger -> BigIntegerNode.valueOf(value)
    is java.math.BigDecimal -> DecimalNode.valueOf(value)
    else -> error("Unsupported JSON number: ${value.javaClass.name}")
}
fun ObjectNode(values: Map<String, JsonNode>): ObjectNode = JsonNodeFactory.instance.objectNode().setAll<ObjectNode>(values)
fun ArrayNode(values: Collection<JsonNode>): ArrayNode = JsonNodeFactory.instance.arrayNode().addAll(values)

class JsonObjectBuilder {
    private val values = linkedMapOf<String, JsonNode>()
    fun put(key: String, value: JsonNode?): JsonNode? = values.put(key, value ?: NullNode.instance)
    fun put(key: String, value: String?) = put(key, jsonValue(value))
    fun put(key: String, value: Boolean?) = put(key, jsonValue(value))
    fun put(key: String, value: Number?) = put(key, jsonValue(value))
    fun putJsonObject(key: String, block: JsonObjectBuilder.() -> Unit) = put(key, buildJsonObject(block))
    fun build(): ObjectNode = ObjectNode(values)
}
class JsonArrayBuilder {
    private val values = mutableListOf<JsonNode>()
    fun add(value: JsonNode) { values.add(value) }
    fun add(value: String) = add(jsonValue(value))
    fun add(value: Number) = add(jsonValue(value))
    fun add(value: Boolean) = add(jsonValue(value))
    fun addJsonObject(block: JsonObjectBuilder.() -> Unit) = add(buildJsonObject(block))
    fun build(): ArrayNode = ArrayNode(values)
}
inline fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): ObjectNode = JsonObjectBuilder().apply(block).build()
inline fun buildJsonArray(block: JsonArrayBuilder.() -> Unit): ArrayNode = JsonArrayBuilder().apply(block).build()
val JsonNode.jsonObject: ObjectNode get() = this as? ObjectNode ?: error("Expected object: $this")
val JsonNode.jsonArray: ArrayNode get() = this as? ArrayNode ?: error("Expected array: $this")
val JsonNode.jsonPrimitive: JsonNode get() = apply { require(isValueNode) { "Expected scalar: $this" } }
val JsonNode.content: String get() = asText()
val JsonNode.contentOrNull: String? get() = if (isNull) null else asText()
val JsonNode.isString: Boolean get() = isTextual
val JsonNode.booleanOrNull: Boolean? get() = if (isBoolean) booleanValue() else if (isTextual) textValue().toBooleanStrictOrNull() else null
val JsonNode.boolean: Boolean get() = booleanOrNull ?: error("Expected boolean: $this")
val JsonNode.int: Int get() = content.toInt()
val JsonNode.double: Double get() = content.toDouble()
val JsonNode.doubleOrNull: Double? get() = content.toDoubleOrNull()
val JsonNode.size: Int get() = size()
val ObjectNode.entries: Set<Map.Entry<String, JsonNode>> get() = properties()
val ObjectNode.keys: Set<String> get() = properties().mapTo(linkedSetOf()) { it.key }
fun ObjectNode.getValue(key: String): JsonNode = get(key) ?: error("Missing property '$key'")
fun ObjectNode.toMap(): Map<String, JsonNode> = properties().associate { it.key to it.value }
fun ObjectNode.isNotEmpty(): Boolean = !isEmpty
fun ObjectNode.containsKey(key: String): Boolean = has(key)
operator fun ObjectNode.plus(other: ObjectNode): Map<String, JsonNode> = toMap() + other.toMap()
operator fun ObjectNode.plus(other: Pair<String, JsonNode>): Map<String, JsonNode> = toMap() + other
operator fun ObjectNode.plus(other: Map<String, JsonNode>): Map<String, JsonNode> = toMap() + other
fun <R> ObjectNode.mapValues(transform: (Map.Entry<String, JsonNode>) -> R): Map<String, R> = properties().associate { it.key to transform(it) }
fun ObjectNode.filterKeys(predicate: (String) -> Boolean): Map<String, JsonNode> = toMap().filterKeys(predicate)
val ArrayNode.indices: IntRange get() = 0 until size()

/** Normalize native scalar types to their JSON data equivalents. */
fun JsonNode.normalizeRuntimeTree(): JsonNode = when {
    isBinary -> TextNode.valueOf(asText())
    (isDouble || isFloat) && !doubleValue().isFinite() -> TextNode.valueOf(asText())
    isFloatingPointNumber -> DecimalNode.valueOf(decimalValue())
    isLong && canConvertToInt() -> IntNode.valueOf(intValue())
    isObject -> (this as ObjectNode).apply { properties().toList().forEach { set<JsonNode>(it.key, it.value.normalizeRuntimeTree()) } }
    isArray -> (this as ArrayNode).apply { indices.forEach { set(it, get(it).normalizeRuntimeTree()) } }
    else -> this
}

/** Prevent compact exponent notation from expanding into unbounded validator strings. */
fun validateDecimalBounds(node: JsonNode) {
    if (node.isFloatingPointNumber) {
        val number = node.decimalValue()
        require(number.precision().toLong() + kotlin.math.abs(number.scale().toLong()) <= 10_000) {
            "Decimal expansion exceeds 10000 digits"
        }
    }
    if (node.isContainerNode) node.forEach(::validateDecimalBounds)
}

/** Persisted correlation keys used Jackson's Double representation before migration. */
fun JsonNode.toLegacyCorrelationNode(): JsonNode = when {
    isFloatingPointNumber -> DoubleNode.valueOf(doubleValue())
    isObject -> ObjectNode(properties().associate { it.key to it.value.toLegacyCorrelationNode() })
    isArray -> ArrayNode(map { it.toLegacyCorrelationNode() })
    else -> this
}

fun JsonNode.toCorrelationValue(): String? {
    val legacy = toLegacyCorrelationNode()
    return when {
        legacy.isNull -> null
        legacy.isValueNode -> legacy.asText()
        // Retain the historical JSON escaping for structured persisted keys.
        else -> legacy.toKotlinTree().toString()
    }
}
