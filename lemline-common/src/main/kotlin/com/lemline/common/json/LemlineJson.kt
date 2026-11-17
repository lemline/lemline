// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.serverlessworkflow.api.types.ErrorDetails
import io.serverlessworkflow.api.types.ErrorTitle
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.HTTPHeaders
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
import io.serverlessworkflow.api.types.Schedule
import io.serverlessworkflow.api.types.SetTaskConfiguration
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.api.types.jackson.JacksonMixInModule
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.serialization.BeanDeserializerModifierWithValidation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

object LemlineJson {

    // Expose Jackson ObjectMapper - configure as needed
    val jacksonMapper = ObjectMapper().apply {
        enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        registerModule(JacksonMixInModule())
        // SDK union deserialization relies on bean validation to distinguish
        // named function calls from built-in protocol calls.
        registerModule(SimpleModule().setDeserializerModifier(BeanDeserializerModifierWithValidation()))
        addMixIn(Schedule::class.java, ScheduleMixIn::class.java)
        addMixIn(ErrorDetails::class.java, ErrorDetailsMixIn::class.java)
        addMixIn(ErrorTitle::class.java, ErrorTitleMixIn::class.java)
        // SDK types no longer carry Jackson annotations. Omit absent bean properties,
        // but preserve explicit null map values and empty arrays in workflow data.
        setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
        configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    }

    // Expose Jackson YAMLMapper
    val yamlMapper = YAMLMapper().apply {
        enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        registerModule(JacksonMixInModule())
        registerModule(SimpleModule().setDeserializerModifier(BeanDeserializerModifierWithValidation()))
        addMixIn(Schedule::class.java, ScheduleMixIn::class.java)
        addMixIn(ErrorDetails::class.java, ErrorDetailsMixIn::class.java)
        addMixIn(ErrorTitle::class.java, ErrorTitleMixIn::class.java)
        setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
    }

    // The SDK initializes read=data even for timers, but the schema permits read
    // only with schedule.on. Omitting the default also preserves event semantics.
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private abstract class ScheduleMixIn

    // simply create a new ObjectNode
    val jsonObject: ObjectNode get() = ObjectNode(emptyMap())

    // Define the serializers module
    private val module = SerializersModule {
        contextual(InstantSerializer)
        contextual(UUIDSerializer)
        // Add other contextual serializers here
    }

    // Configure a kotlinx.serialization.Json instance with the module
    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        serializersModule = module
    }

    val jsonPretty = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        serializersModule = module
        prettyPrint = true
    }

    /**
     * Converts an object to its JSON string representation using the configured 'json' instance.
     */
    inline fun <reified T> encodeToString(value: T): String = if (value is JsonNode) value.toString() else json.encodeToString(value)

    /**
     * Converts an object to its pretty JSON string representation using the configured 'jsonPretty' instance.
     */
    inline fun <reified T> encodeToPrettyString(value: T): String = if (value is JsonNode) jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) else jsonPretty.encodeToString(value)

    /**
     * Converts an object to a JSON element using the configured 'json' instance.
     */
    inline fun <reified T> encodeToElement(value: T): JsonNode = if (value is JsonNode) value else json.encodeToJsonElement(value).toJacksonNode()

    /**
     * Creates an object from its JSON string representation using the configured 'json' instance.
     */
    inline fun <reified T> decodeFromString(jsonString: String): T = if (JsonNode::class.java.isAssignableFrom(T::class.java)) jacksonMapper.readTree(jsonString).also(::validateDecimalBounds) as T else json.decodeFromString(jsonString)

    /**
     * Creates an object from its JSON element representation using the configured 'json' instance.
     */
    inline fun <reified T> decodeFromElement(jsonElement: JsonNode): T = if (JsonNode::class.java.isAssignableFrom(T::class.java)) jsonElement as T else json.decodeFromJsonElement(jsonElement.toKotlinTree())

    /**
     * Encodes a `TaskBase` object into a `ObjectNode`.
     */
    fun encodeToElement(task: TaskBase) = (jacksonMapper.valueToTree<ObjectNode>(task).normalizeRuntimeTree() as ObjectNode)

    /**
     * Encodes a `Workflow` object into a `ObjectNode`.
     */
    fun encodeToElement(workflow: Workflow) = (jacksonMapper.valueToTree<ObjectNode>(workflow).normalizeRuntimeTree() as ObjectNode)

    /**
     * Encodes a `SetTaskConfiguration` object into a `JsonNode`.
     */
    fun encodeToElement(set: SetTaskConfiguration) = set.additionalProperties.toJsonNode()

    /**
     * Encodes an `ExportAs` object into a `JsonNode`.
     */
    fun encodeToElement(exportAs: ExportAs) = exportAs.get().toJsonNode()

    /**
     * Encodes an `InputFrom` object into a `JsonNode`.
     */
    fun encodeToElement(inputFrom: InputFrom) = inputFrom.get().toJsonNode()

    /**
     * Encodes an `OutputAs` object into a `JsonNode`.
     */
    fun encodeToElement(outputAs: OutputAs) = outputAs.get().toJsonNode()

    /**
     * Encodes an `HTTPHeaders` object into a `Map<String, String>`.
     */
    fun encodeToString(httpHeaders: HTTPHeaders?): Map<String, String> = httpHeaders?.additionalProperties?.mapValues {
        it.value.toJsonPrimitive().content
    } ?: emptyMap()

    /**
     * Encodes a `DateTimeDescriptor` object into a `ObjectNode`.
     */
    fun encodeToElement(dateTimeDescriptor: DateTimeDescriptor) = dateTimeDescriptor.toJsonNode() as ObjectNode

    fun Any?.toJsonNode(): JsonNode = when (this) {
        null -> NullNode.instance
        is JsonNode -> this
        is Number -> jsonValue(this)
        is String -> jsonValue(this)
        is Boolean -> jsonValue(this)
        is Map<*, *> -> buildJsonObject {
            this@toJsonNode.forEach { (key, value) -> put(key as String, value.toJsonNode()) }
        }

        is Collection<*> -> ArrayNode(this.map { it.toJsonNode() })
        is DateTimeDescriptor -> jacksonMapper.valueToTree<ObjectNode>(this).normalizeRuntimeTree()

        else -> throw IllegalArgumentException("Unsupported type: ${this::class}")
    }

    fun Any?.toJsonPrimitive(): JsonNode = when (val element = this.toJsonNode()) {
        is com.fasterxml.jackson.databind.node.ValueNode -> element
        else -> jsonValue(element.toString())
    }
}

/**
 * Extension function to parse a JSON string into a JsonNode.
 */
fun String.toJsonNode(): JsonNode = parseJson(this)
