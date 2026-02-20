// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
/**
 * Configuration for emitting a CloudEvent.
 *
 * Contains all data needed to build a CloudEvent using the CloudEvents SDK.
 * The ActivityExecutor uses this config to construct and publish the event.
 */
data class EmitConfig(
    val id: String,
    val source: String,
    val type: String,
    val time: String? = null,
    val subject: String? = null,
    val dataschema: String? = null,
    val datacontenttype: String? = null,
    val data: JsonElement? = null,
    val extensions: Map<String, String>? = null
)
/**
 * Configuration for making an HTTP call.
 *
 * Contains all data needed to execute an HTTP request.
 * Authentication is resolved at config-building time.
 */
data class CallHttpConfig(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    val output: HTTPOutput = HTTPOutput.CONTENT,
    val redirect: Boolean = true,
    val authentication: HttpAuthentication? = null
)
/**
 * Resolved authentication data for HTTP calls.
 * Authentication policies are resolved when building the config.
 */
sealed class HttpAuthentication {
    data class Basic(val username: String, val password: String) : HttpAuthentication()
    data class Bearer(val token: String) : HttpAuthentication()
    data class OAuth2(
        val token: String,
        val tokenType: String = "Bearer"
    ) : HttpAuthentication()
}
/**
 * Configuration for running a script.
 *
 * Contains all data needed to execute a script in a supported language.
 */
data class RunScriptConfig(
    val language: String,
    val code: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val await: Boolean = true,
    val returnType: ProcessReturnType = ProcessReturnType.STDOUT
)
/**
 * Configuration for running a shell command.
 *
 * Contains all data needed to execute a shell command.
 */
data class RunShellConfig(
    val command: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val await: Boolean = true,
    val returnType: ProcessReturnType = ProcessReturnType.STDOUT
)
// ========================================
// Listen Task Configuration
// ========================================
/**
 * Strategy for consuming events in a listen task.
 */
enum class ListenStrategy {
    /** Wait for a single event matching the filter */
    ONE,
    /** Wait for first event matching any filter, or accumulate with until */
    ANY,
    /** Wait for one event per filter */
    ALL
}
/**
 * Custom serializer for [ListenAndReadAs] Java enum.
 * Serializes to/from the string value ("data", "envelope", "raw").
 */
internal object ListenAndReadAsSerializer : KSerializer<ListenAndReadAs> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ListenAndReadAs", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ListenAndReadAs) {
        encoder.encodeString(value.value())
    }
    override fun deserialize(decoder: Decoder): ListenAndReadAs {
        return ListenAndReadAs.fromValue(decoder.decodeString())
    }
}
/**
 * Correlation definition for linking events to workflow instances.
 *
 * @property from JQ expression to extract correlation value from event data
 * @property expect Optional expected value expression evaluated against workflow context.
 *                  If absent, first event sets the baseline (Mode 2).
 */
data class CorrelationDef(
    val from: String,
    val expect: String? = null
)
/**
 * Event filter for matching CloudEvents.
 *
 * @property type CloudEvent type (exact match)
 * @property source CloudEvent source URI or expression
 * @property subject CloudEvent subject
 * @property id CloudEvent id
 * @property datacontenttype Content type filter
 * @property dataschema Data schema URI or expression
 * @property time Event timestamp expression
 * @property dataFilter JQ expression evaluated against event.data
 * @property correlations Map of correlation definitions by key name
 */
data class EventFilter(
    val type: String? = null,
    val source: String? = null,
    val subject: String? = null,
    val id: String? = null,
    val datacontenttype: String? = null,
    val dataschema: String? = null,
    val time: String? = null,
    val dataFilter: String? = null,
    val correlations: Map<String, CorrelationDef>? = null
)
/**
 * Until condition for accumulation mode.
 * Can be either an expression evaluated against accumulated events,
 * or an event filter that terminates when matched.
 */
sealed class UntilCondition {
    /** Expression evaluated against accumulated events array (e.g., ". | any(.temp > 38)") */
    data class Expression(val expression: String) : UntilCondition()
    /** Event filter - stop when this event arrives */
    data class Event(val filter: EventFilter) : UntilCondition()
}
/**
 * Configuration for listening to CloudEvents.
 *
 * Contains all data needed to set up an event listener. The runner uses this
 * config to create listener entries in the database.
 *
 * @property strategy Event consumption strategy (one, any, all)
 * @property filters List of event filters. Empty list = wildcard (any event matches)
 * @property until Optional accumulation termination condition
 * @property readAs How to extract event content (data, envelope, raw)
 * @property timeoutAt Optional absolute timeout timestamp
 * @property correlationContext Workflow context data for evaluating correlate.expect expressions.
 *                              Only values needed for correlation are included.
 */
@OptIn(ExperimentalTime::class)
data class ListenConfig(
    val strategy: ListenStrategy,
    val filters: List<EventFilter>,
    val until: UntilCondition? = null,
    val readAs: ListenAndReadAs = ListenAndReadAs.DATA,
    @Contextual val timeoutAt: Instant? = null,
    val correlationContext: JsonElement? = null
)
