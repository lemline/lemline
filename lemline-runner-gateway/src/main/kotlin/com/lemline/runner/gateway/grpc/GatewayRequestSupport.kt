// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.google.protobuf.Timestamp as ProtoTimestamp
import com.lemline.common.values.IDV7
import com.lemline.gateway.v1.InstanceStatus as ProtoInstanceStatus
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayAuthenticationEnabled
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.resources.instances.InstanceStatus
import java.time.Instant
import java.util.*

internal fun allowedNamespacesForRead(
    config: LemlineConfiguration,
    authorizer: GatewayAuthorizer,
    principal: GatewayPrincipal,
    explicitNamespace: String?,
): Set<String>? {
    explicitNamespace?.let {
        authorizer.requireNamespace(principal, it)
        return setOf(it)
    }

    if (!config.gatewayAuthenticationEnabled) return null
    if (principal.namespaces.contains("*")) return null
    return principal.namespaces
}

internal fun requiredString(raw: String, field: String): String =
    raw.trim().ifBlank { throw GatewayBadRequestException("$field must be provided") }

internal fun optionalString(hasField: Boolean, raw: String): String? =
    if (!hasField) {
        null
    } else {
        raw.trim().ifBlank { null }
    }

internal fun parseInstanceUuid(raw: String, field: String): UUID {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) {
        throw GatewayBadRequestException("$field must be provided")
    }

    return try {
        IDV7.from(trimmed).value
    } catch (_: Exception) {
        throw GatewayBadRequestException("$field must be a valid UUIDv7 string")
    }
}

internal fun optionalInstanceStatus(hasField: Boolean, raw: ProtoInstanceStatus): InstanceStatus? {
    if (!hasField) return null
    return when (raw) {
        ProtoInstanceStatus.INSTANCE_STATUS_PENDING -> InstanceStatus.PENDING
        ProtoInstanceStatus.INSTANCE_STATUS_RUNNING -> InstanceStatus.RUNNING
        ProtoInstanceStatus.INSTANCE_STATUS_COMPLETED -> InstanceStatus.COMPLETED
        ProtoInstanceStatus.INSTANCE_STATUS_FAULTED -> InstanceStatus.FAULTED
        ProtoInstanceStatus.INSTANCE_STATUS_UNSPECIFIED,
        ProtoInstanceStatus.UNRECOGNIZED -> throw GatewayBadRequestException(
            "status must be one of PENDING, RUNNING, COMPLETED, or FAULTED"
        )
    }
}

internal fun requiredInstant(hasField: Boolean, raw: ProtoTimestamp, field: String): Instant {
    if (!hasField) {
        throw GatewayBadRequestException("$field must be provided")
    }
    return optionalInstant(true, raw) ?: throw GatewayBadRequestException("$field must be provided")
}

internal fun optionalInstant(hasField: Boolean, raw: ProtoTimestamp): Instant? =
    if (!hasField) {
        null
    } else {
        Instant.ofEpochSecond(raw.seconds, raw.nanos.toLong())
    }

internal fun validateTimeRange(timeFrom: Instant, timeTo: Instant) {
    if (!timeFrom.isBefore(timeTo)) {
        throw GatewayBadRequestException("time_from must be before time_to")
    }
}

internal fun validateCreatedRange(createdFrom: Instant?, createdTo: Instant?) {
    if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
        throw GatewayBadRequestException("created_from must be before created_to")
    }
}

internal fun normalizeAllowedNamespaces(namespaces: Set<String>?): List<String>? =
    namespaces?.toList()?.sorted()

internal fun parseCursorInstant(raw: String): Instant = try {
    Instant.parse(raw)
} catch (_: Exception) {
    throw GatewayBadRequestException("Invalid cursor")
}
