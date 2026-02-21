// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.auth

import io.grpc.Context

object GatewayAuthContext {
    private val principalKey: Context.Key<GatewayPrincipal> = Context.key("lemline-gateway-principal")

    fun set(principal: GatewayPrincipal): Context = Context.current().withValue(principalKey, principal)

    fun getOrNull(): GatewayPrincipal? = principalKey.get()
}
