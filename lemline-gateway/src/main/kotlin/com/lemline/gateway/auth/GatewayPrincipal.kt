// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.auth

data class GatewayPrincipal(
    val subject: String?,
    val scopes: Set<String>,
    val namespaces: Set<String>,
) {
    fun hasScope(scope: String): Boolean = scopes.contains(scope) || scopes.contains("*")

    fun canAccessNamespace(namespace: String): Boolean = namespaces.contains("*") || namespaces.contains(namespace)
}
