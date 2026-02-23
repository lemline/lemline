// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_ENABLED_DEFAULT
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_SCOPE_FIELD
import com.lemline.runner.gateway.config.GatewayConfigConstants.GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class GatewayAuthorizer(
    @ConfigProperty(name = GATEWAY_AUTHENTICATION_ENABLED, defaultValue = GATEWAY_AUTHENTICATION_ENABLED_DEFAULT)
    private val authenticationEnabled: Boolean,
    @ConfigProperty(name = GATEWAY_AUTHENTICATION_SCOPE_FIELD, defaultValue = GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT)
    private val scopeField: String,
    @ConfigProperty(
        name = GATEWAY_AUTHENTICATION_NAMESPACES_FIELD,
        defaultValue = GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT
    )
    private val namespacesField: String,
) {
    fun principalFrom(jwt: JsonWebToken): GatewayPrincipal {
        if (!authenticationEnabled) {
            return GatewayPrincipal(
                subject = null,
                scopes = setOf("*"),
                namespaces = setOf("*")
            )
        }

        val scopes = parseClaimAsSet(jwt, scopeField)
        val namespaces = parseClaimAsSet(jwt, namespacesField)
        return GatewayPrincipal(
            subject = jwt.name,
            scopes = scopes,
            namespaces = namespaces
        )
    }

    fun requireScope(principal: GatewayPrincipal, scope: String) {
        if (!authenticationEnabled) return
        if (!principal.hasScope(scope)) {
            throw GatewayPermissionDeniedException("Missing required scope '$scope'")
        }
    }

    fun requireNamespace(principal: GatewayPrincipal, namespace: String) {
        if (!authenticationEnabled) return
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
