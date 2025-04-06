package com.lemline.common.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

internal object Json {
    // Expose Jackson ObjectMapper - configure as needed
    val jacksonMapper: ObjectMapper = ObjectMapper()

    val jsonObject: JsonObject get() = JsonObject(emptyMap())

    // Define the serializers module
    private val module = SerializersModule {
        contextual(InstantSerializer)
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

    fun encodeToElement(task: TaskBase): JsonObject =
        decodeFromString(jacksonMapper.writeValueAsString(task)) as JsonObject

    fun encodeToElement(workflow: Workflow): JsonObject =
        decodeFromString(jacksonMapper.writeValueAsString(workflow)) as JsonObject

    fun encodeToElement(set: SetTaskConfiguration) = set.additionalProperties.toJsonElement()

    fun encodeToElement(exportAs: ExportAs) = exportAs.get().toJsonElement()

    fun encodeToElement(inputFrom: InputFrom) = inputFrom.get().toJsonElement()

    fun encodeToElement(outputAs: OutputAs) = outputAs.get().toJsonElement()

    fun encodeToElement(dateTimeDescriptor: DateTimeDescriptor) = dateTimeDescriptor.toJsonElement() as JsonObject

    fun JsonElement.toJsonNode(): JsonNode = jacksonMapper.readTree(toString())

    fun JsonNode.toJsonElement(): JsonElement = decodeFromString(toString())

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is Number -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Map<*, *> -> buildJsonObject {
                this@toJsonElement.forEach { (key, value) -> put(key as String, value.toJsonElement()) }
            }

            is DateTimeDescriptor -> decodeFromString(jacksonMapper.writeValueAsString(this))

            else -> throw IllegalArgumentException("Unsupported type: ${this::class}")
        }
}



