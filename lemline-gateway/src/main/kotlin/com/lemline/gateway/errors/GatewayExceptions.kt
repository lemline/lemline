// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.errors

class GatewayBadRequestException(message: String) : RuntimeException(message)

class GatewayNotFoundException(message: String) : RuntimeException(message)

class GatewayConflictException(message: String) : RuntimeException(message)

class GatewayPermissionDeniedException(message: String) : RuntimeException(message)
