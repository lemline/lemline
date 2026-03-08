// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.lemline.common.json.LemlineJson
import com.lemline.runner.gateway.definitions.DefinitionPageBoundary
import com.lemline.runner.gateway.definitionstats.DefinitionStatsPageBoundary
import com.lemline.runner.gateway.instances.InstancePageBoundary
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

object GatewayCursorCodec {
    fun encodeDefinitionPageCursor(cursor: DefinitionPageCursor): String = encode(cursor)

    fun decodeDefinitionPageCursor(raw: String): DefinitionPageCursor =
        decode<DefinitionPageCursor>(raw).also {
            require(it.kind == DefinitionPageCursor.KIND) { "Invalid cursor" }
        }

    fun encodeDefinitionStatsPageCursor(cursor: DefinitionStatsPageCursor): String = encode(cursor)

    fun decodeDefinitionStatsPageCursor(raw: String): DefinitionStatsPageCursor =
        decode<DefinitionStatsPageCursor>(raw).also {
            require(it.kind == DefinitionStatsPageCursor.KIND) { "Invalid cursor" }
        }

    fun encodeDefinitionStatsWatchCursor(cursor: DefinitionStatsWatchCursor): String = encode(cursor)

    fun decodeDefinitionStatsWatchCursor(raw: String): DefinitionStatsWatchCursor =
        decode<DefinitionStatsWatchCursor>(raw).also {
            require(it.kind == DefinitionStatsWatchCursor.KIND) { "Invalid cursor" }
        }

    fun encodeInstancePageCursor(cursor: InstancePageCursor): String = encode(cursor)

    fun decodeInstancePageCursor(raw: String): InstancePageCursor =
        decode<InstancePageCursor>(raw).also {
            require(it.kind == InstancePageCursor.KIND) { "Invalid cursor" }
        }

    fun encodeInstanceWatchCursor(cursor: InstanceWatchCursor): String = encode(cursor)

    fun decodeInstanceWatchCursor(raw: String): InstanceWatchCursor =
        decode<InstanceWatchCursor>(raw).also {
            require(it.kind == InstanceWatchCursor.KIND) { "Invalid cursor" }
        }

    fun encodeInstanceEventCursor(cursor: InstanceEventCursor): String = encode(cursor)

    fun decodeInstanceEventCursor(raw: String): InstanceEventCursor =
        decode<InstanceEventCursor>(raw).also {
            require(it.kind == InstanceEventCursor.KIND) { "Invalid cursor" }
        }

    private inline fun <reified T> encode(value: T): String {
        val json = LemlineJson.encodeToString(value)
        return java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    private inline fun <reified T> decode(raw: String): T {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "cursor must not be blank" }
        val decoded = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(trimmed), StandardCharsets.UTF_8)
        }.getOrElse {
            throw IllegalArgumentException("Invalid cursor")
        }
        return runCatching { LemlineJson.decodeFromString<T>(decoded) }
            .getOrElse { throw IllegalArgumentException("Invalid cursor") }
    }
}

@Serializable
data class DefinitionPageCursor(
    val kind: String = KIND,
    val namespace: String? = null,
    val name: String? = null,
    val latestOnly: Boolean,
    val allowedNamespaces: List<String>? = null,
    val snapshotAt: String,
    val lastNamespace: String,
    val lastName: String,
    val lastVersion: String,
) {
    companion object {
        const val KIND = "definition_page_v1"
    }
}

@Serializable
data class DefinitionStatsPageCursor(
    val kind: String = KIND,
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val allowedNamespaces: List<String>? = null,
    val timeFrom: String,
    val timeTo: String,
    val snapshotSequence: Long,
    val lastNamespace: String,
    val lastName: String,
    val lastVersion: String,
) {
    companion object {
        const val KIND = "definition_stats_page_v1"
    }
}

@Serializable
data class DefinitionStatsWatchCursor(
    val kind: String = KIND,
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val allowedNamespaces: List<String>? = null,
    val timeFrom: String,
    val timeTo: String,
    val sequence: Long,
) {
    companion object {
        const val KIND = "definition_stats_watch_v1"
    }
}

@Serializable
data class InstancePageCursor(
    val kind: String = KIND,
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val status: String? = null,
    val createdFrom: String? = null,
    val createdTo: String? = null,
    val instanceIdPrefix: String? = null,
    val allowedNamespaces: List<String>? = null,
    val snapshotSequence: Long,
    val lastCreatedAt: String,
    val lastInstanceId: String,
) {
    companion object {
        const val KIND = "instance_page_v1"
    }
}

@Serializable
data class InstanceWatchCursor(
    val kind: String = KIND,
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null,
    val status: String? = null,
    val createdFrom: String? = null,
    val createdTo: String? = null,
    val instanceIdPrefix: String? = null,
    val allowedNamespaces: List<String>? = null,
    val sequence: Long,
) {
    companion object {
        const val KIND = "instance_watch_v1"
    }
}

@Serializable
data class InstanceEventCursor(
    val kind: String = KIND,
    val instanceId: String,
    val sequence: Long,
) {
    companion object {
        const val KIND = "instance_event_v1"
    }
}

internal fun DefinitionPageCursor.toBoundary(): DefinitionPageBoundary =
    DefinitionPageBoundary(
        namespace = lastNamespace,
        name = lastName,
        version = lastVersion,
    )

internal fun DefinitionStatsPageCursor.toBoundary(): DefinitionStatsPageBoundary =
    DefinitionStatsPageBoundary(
        namespace = lastNamespace,
        name = lastName,
        version = lastVersion,
    )

internal fun InstancePageCursor.toBoundary(): InstancePageBoundary =
    InstancePageBoundary(
        createdAt = Instant.parse(lastCreatedAt),
        instanceId = UUID.fromString(lastInstanceId),
    )
