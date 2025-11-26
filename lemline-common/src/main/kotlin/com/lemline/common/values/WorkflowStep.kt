// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Dynamic workflow step identifier that includes visit counts.
 *
 * Extends static NodePosition with visit counts to uniquely identify each
 * execution instance, even when the same node is visited multiple times
 * (loops, retries, parallel branches).
 *
 * Format: /{nodeName},{visitCount}/{nodeName},{visitCount}/...
 * Example: /do,0/taskA,0 or /for,2/do,1/processItem,0
 *
 * This differs from NodePosition which only stores the static structure:
 * - NodePosition: /do/taskA (static definition location)
 * - WorkflowStep: /do,0/taskA,0 (specific execution instance with visit counts)
 *
 * @property path The path string in format /{name},{count}/{name},{count}/...
 */
@Serializable(with = WorkflowStepSerializer::class)
data class WorkflowStep(private val path: String) {

    init {
        require(path.startsWith("/")) {
            "WorkflowStep path must start with '/', got: '$path'"
        }
        // Validate format: each segment should be name,count
        val segments = path.substring(1).split("/")
        require(segments.isNotEmpty()) {
            "WorkflowStep must have at least one segment"
        }
        segments.forEach { segment ->
            val parts = segment.split(",")
            require(parts.size == 2) {
                "Each segment must be in format 'name,count', got: '$segment' in path: '$path'"
            }
            val (name, count) = parts
            require(name.isNotEmpty()) {
                "Name must not be empty in segment: '$segment'"
            }
            require(count.all { it.isDigit() }) {
                "Visit count must be numeric, got: '$count' in segment: '$segment'"
            }
        }
    }

    /**
     * Convert to static NodePosition by removing visit counts.
     *
     * Example: "/do,0/taskA,0" → NodePosition("/do/taskA")
     *
     * @return The static NodePosition without visit counts
     */
    fun toNodePosition(): NodePosition {
        val segments = path.substring(1).split("/")
        val nameSegments = segments.map { it.substringBefore(',') }
        return NodePosition.parse("/" + nameSegments.joinToString("/"))
    }

    /**
     * Get the string representation of the workflow step.
     * @return The workflow step path (e.g., "/do,0/taskA,0")
     */
    fun toJsonString(): String = path

    /**
     * Get the string representation of the workflow step.
     * @return The workflow step path (e.g., "/do,0/taskA,0")
     */
    override fun toString(): String = path

    companion object {
        /**
         * Deserialize from JSON string.
         */
        fun fromJsonString(jsonString: String): WorkflowStep =
            LemlineJson.decodeFromString(jsonString)
    }
}

/**
 * Custom kotlinx.serialization serializer for [WorkflowStep].
 * Serializes to/from the string representation of the workflow step path.
 */
internal object WorkflowStepSerializer : KSerializer<WorkflowStep> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WorkflowStep", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WorkflowStep) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): WorkflowStep {
        return WorkflowStep(decoder.decodeString().trim())
    }
}
