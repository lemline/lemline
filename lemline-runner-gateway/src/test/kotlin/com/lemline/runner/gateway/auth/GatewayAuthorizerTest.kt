// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.gateway.support.testLemlineConfiguration
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayAuthorizerTest {

    private fun scopeOf(vararg namespaces: String): NamespaceScope = object : NamespaceScope {
        override val configured: Set<String>? = namespaces.toSet().ifEmpty { null }
    }

    @Test
    fun `security disabled allows all scope and namespace checks`() {
        val authorizer = GatewayAuthorizer(
            config = testLemlineConfiguration(
                authenticationEnabled = false,
                authenticationScopeField = "scope",
                authenticationNamespacesField = "lemline_namespaces",
            ),
            namespaceScope = scopeOf(),
        )

        val principal = GatewayPrincipal(
            subject = "any",
            scopes = emptySet(),
            namespaces = emptySet(),
        )

        authorizer.requireScope(principal, "lemline.start")
        authorizer.requireNamespace(principal, "private")
    }

    @Test
    fun `pinned gateway without authentication enforces the pin`() {
        val authorizer = GatewayAuthorizer(
            config = testLemlineConfiguration(
                authenticationEnabled = false,
                authenticationScopeField = "scope",
                authenticationNamespacesField = "lemline_namespaces",
            ),
            namespaceScope = scopeOf("team-a"),
        )

        val principal = authorizer.unauthenticatedPrincipal()
        assertEquals(setOf("team-a"), principal.namespaces)

        authorizer.requireNamespace(principal, "team-a")
        assertFailsWith<GatewayPermissionDeniedException> {
            authorizer.requireNamespace(principal, "team-b")
        }
    }

    @Test
    fun `security enabled preserves scope and namespace enforcement`() {
        val authorizer = GatewayAuthorizer(
            config = testLemlineConfiguration(
                authenticationEnabled = true,
                authenticationScopeField = "scope",
                authenticationNamespacesField = "lemline_namespaces",
            ),
            namespaceScope = scopeOf(),
        )

        val principal = GatewayPrincipal(
            subject = "restricted",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("team-a"),
        )

        assertFailsWith<GatewayPermissionDeniedException> {
            authorizer.requireScope(principal, "lemline.start")
        }

        assertFailsWith<GatewayPermissionDeniedException> {
            authorizer.requireNamespace(principal, "team-b")
        }
    }
}
