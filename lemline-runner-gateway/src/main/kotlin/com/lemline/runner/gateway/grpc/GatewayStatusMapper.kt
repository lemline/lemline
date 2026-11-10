// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import io.grpc.Status
import io.grpc.StatusRuntimeException

internal object GatewayStatusMapper {
    fun toStatusException(t: Throwable): StatusRuntimeException {
        if (t is StatusRuntimeException) return t

        val (status, message) = when (t) {
            is GatewayBadRequestException -> Status.INVALID_ARGUMENT to t.message
            is GatewayNotFoundException -> Status.NOT_FOUND to t.message
            is GatewayConflictException -> Status.ALREADY_EXISTS to t.message
            is GatewayPermissionDeniedException -> Status.PERMISSION_DENIED to t.message
            is IllegalArgumentException -> Status.INVALID_ARGUMENT to t.message
            else -> Status.INTERNAL to (t.message ?: "Unhandled gateway error")
        }

        return status.withDescription(message).withCause(t).asRuntimeException()
    }
}
