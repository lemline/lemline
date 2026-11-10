// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayAuthenticationEnabled
import com.lemline.runner.config.gatewayAuthenticationNamespacesField
import com.lemline.runner.config.gatewayAuthenticationScopeField
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class GatewayAuthorizer(
    private val config: LemlineConfiguration,
    private val namespaceScope: NamespaceScope,
) {
    private val authenticationEnabled: Boolean get() = config.gatewayAuthenticationEnabled
    private val scopeField: String get() = config.gatewayAuthenticationScopeField
    private val namespacesField: String get() = config.gatewayAuthenticationNamespacesField

    /** Optional gateway pin (`lemline.namespaces`): when set, the config pin always wins. */
    private val pin: Set<String>? get() = namespaceScope.configured

    /**
     * Principal for requests without authentication: unrestricted, or bounded by the pin.
     * Always use this instead of a static "anonymous" principal — a pinned gateway must
     * constrain unauthenticated callers too.
     */
    fun unauthenticatedPrincipal(): GatewayPrincipal = GatewayPrincipal(
        subject = null,
        scopes = setOf("*"),
        namespaces = pin ?: setOf("*")
    )

    fun principalFrom(jwt: JsonWebToken): GatewayPrincipal {
        if (!authenticationEnabled) {
            return unauthenticatedPrincipal()
        }

        val scopes = parseClaimAsSet(jwt, scopeField)
        val claimNamespaces = parseClaimAsSet(jwt, namespacesField)
        val namespaces = pin?.let { p ->
            // claim ∩ pin; a "*" claim grants the full pin, never more
            if ("*" in claimNamespaces) p else claimNamespaces.intersect(p)
        } ?: claimNamespaces
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
        // The pin is enforced even with authentication disabled — otherwise a pinned gateway
        // without auth would accept starts in any namespace.
        if (!authenticationEnabled && pin == null) return
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
