package com.lemline.core.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

object LemlineJson {
    // Expose Jackson ObjectMapper - configure as needed
    val jacksonMapper: ObjectMapper = ObjectMapper()

    val jsonObject: JsonObject get() = JsonObject(emptyMap())

    // Define the serializers module
    private val module = SerializersModule {
        contextual(InstantSerializer)
        contextual(UUIDSerializer)
        // Potentially add other contextual serializers here
    }

    // Configure kotlinx.serialization Json instances with the module
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        serializersModule = module // Add the module here
    }

    val jsonPretty = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        prettyPrint = true
        serializersModule = module // Add the module here
    }

    /**
     * Converts an object to its JSON string representation using the configured 'json' instance.
     */
    inline fun <reified T> encodeToString(value: T): String = json.encodeToString(value)

    /**
     * Converts an object to its pretty JSON string representation using the configured 'jsonPretty' instance.
     */
    inline fun <reified T> encodeToPrettyString(value: T): String = jsonPretty.encodeToString(value)

    /**
     * Converts an object to a JSON element using the configured 'json' instance.
     */
    inline fun <reified T> encodeToElement(value: T): JsonElement = json.encodeToJsonElement(value)

    /**
     * Creates an object from its JSON string representation using the configured 'json' instance.
     */
    inline fun <reified T> decodeFromString(jsonString: String): T = json.decodeFromString(jsonString)

    /**
     * Creates an object from its JSON element representation using the configured 'json' instance.
     */
    inline fun <reified T> decodeFromElement(jsonElement: JsonElement): T = json.decodeFromJsonElement(jsonElement)

    /**
     * Encodes a `TaskBase` object into a `JsonObject`.
     */
    fun encodeToElement(task: TaskBase) = decodeFromString(jacksonMapper.writeValueAsString(task)) as JsonObject

    /**
     * Encodes a `Workflow` object into a `JsonObject`.
     */
    fun encodeToElement(workflow: Workflow) = decodeFromString(jacksonMapper.writeValueAsString(workflow)) as JsonObject

    /**
     * Encodes a `SetTaskConfiguration` object into a `JsonElement`.
     */
    fun encodeToElement(set: SetTaskConfiguration) = set.additionalProperties.toJsonElement()

    /**
     * Encodes an `ExportAs` object into a `JsonElement`.
     */
    fun encodeToElement(exportAs: ExportAs) = exportAs.get().toJsonElement()

    /**
     * Encodes an `InputFrom` object into a `JsonElement`.
     */
    fun encodeToElement(inputFrom: InputFrom) = inputFrom.get().toJsonElement()

    /**
     * Encodes an `OutputAs` object into a `JsonElement`.
     */
    fun encodeToElement(outputAs: OutputAs) = outputAs.get().toJsonElement()

    /**
     * Encodes an `HTTPQuery` object into a `Map<String, JsonPrimitive>`.
     */
    fun encodeToPrimitive(httpQuery: HTTPQuery?) =
        httpQuery?.additionalProperties?.mapValues { it.value.toJsonPrimitive() } ?: emptyMap()

    /**
     * Encodes an `HTTPHeaders` object into a `Map<String, JsonPrimitive>`.
     */
    fun encodeToPrimitive(httpHeaders: HTTPHeaders?): Map<String, JsonPrimitive> {
        return httpHeaders?.additionalProperties?.mapValues { it.value.toJsonPrimitive() } ?: emptyMap()
    }

    /**
     * Encodes a `DateTimeDescriptor` object into a `JsonObject`.
     */
    fun encodeToElement(dateTimeDescriptor: DateTimeDescriptor) = dateTimeDescriptor.toJsonElement() as JsonObject

    fun JsonElement.toJsonNode(): JsonNode = jacksonMapper.readTree(toString())

    fun JsonNode.toJsonElement(): JsonElement = decodeFromString(toString())

    internal fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is JsonElement -> this
            is Number -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Map<*, *> -> buildJsonObject {
                this@toJsonElement.forEach { (key, value) -> put(key as String, value.toJsonElement()) }
            }

            is DateTimeDescriptor -> decodeFromString(jacksonMapper.writeValueAsString(this))

            else -> throw IllegalArgumentException("Unsupported type: ${this::class}")
        }

    private fun Any?.toJsonPrimitive(): JsonPrimitive =
        when (val element = this.toJsonElement()) {
            is JsonPrimitive -> element
            else -> JsonPrimitive(toString())
        }
}



