// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.auth

import com.lemline.gateway.config.GatewayConfigConstants.GATEWAY_NAMESPACES_FIELD
import com.lemline.gateway.config.GatewayConfigConstants.GATEWAY_NAMESPACES_FIELD_DEFAULT
import com.lemline.gateway.config.GatewayConfigConstants.GATEWAY_SCOPE_FIELD
import com.lemline.gateway.config.GatewayConfigConstants.GATEWAY_SCOPE_FIELD_DEFAULT
import com.lemline.gateway.errors.GatewayPermissionDeniedException
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class GatewayAuthorizer(
    @ConfigProperty(name = GATEWAY_SCOPE_FIELD, defaultValue = GATEWAY_SCOPE_FIELD_DEFAULT)
    private val scopeField: String,
    @ConfigProperty(name = GATEWAY_NAMESPACES_FIELD, defaultValue = GATEWAY_NAMESPACES_FIELD_DEFAULT)
    private val namespacesField: String,
) {
    fun principalFrom(jwt: JsonWebToken): GatewayPrincipal {
        val scopes = parseClaimAsSet(jwt, scopeField)
        val namespaces = parseClaimAsSet(jwt, namespacesField)
        return GatewayPrincipal(
            subject = jwt.name,
            scopes = scopes,
            namespaces = namespaces
        )
    }

    fun requireScope(principal: GatewayPrincipal, scope: String) {
        if (!principal.hasScope(scope)) {
            throw GatewayPermissionDeniedException("Missing required scope '$scope'")
        }
    }

    fun requireNamespace(principal: GatewayPrincipal, namespace: String) {
        if (!principal.canAccessNamespace(namespace)) {
            throw GatewayPermissionDeniedException("Not authorized for namespace '$namespace'")
        }
    }

    private fun parseClaimAsSet(jwt: JsonWebToken, claimName: String): Set<String> {
        if (!jwt.claimNames.contains(claimName)) return emptySet()
        val claim = runCatching { jwt.getClaim<Any?>(claimName) }.getOrNull() ?: return emptySet()

        return when (claim) {
            is String -> claim
                .split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            is Collection<*> -> claim
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            is Array<*> -> claim
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            else -> setOf(claim.toString())
        }
    }
}
