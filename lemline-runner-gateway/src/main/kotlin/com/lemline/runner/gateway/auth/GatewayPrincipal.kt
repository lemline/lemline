// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

data class GatewayPrincipal(
    val subject: String?,
    val scopes: Set<String>,
    val namespaces: Set<String>,
) {
    fun hasScope(scope: String): Boolean = scopes.contains(scope) || scopes.contains("*")

    fun canAccessNamespace(namespace: String): Boolean = namespaces.contains("*") || namespaces.contains(namespace)

    companion object {
        val dashboardAnonymous: GatewayPrincipal = GatewayPrincipal(
            subject = null,
            scopes = setOf("*"),
            namespaces = setOf("*")
        )
    }
}
