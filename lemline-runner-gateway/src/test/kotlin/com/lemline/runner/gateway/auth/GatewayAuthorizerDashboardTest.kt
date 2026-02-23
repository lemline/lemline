// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GatewayAuthorizerDashboardTest {

    @Test
    fun `security disabled allows all scope and namespace checks`() {
        val authorizer = GatewayAuthorizer(
            authenticationEnabled = false,
            scopeField = "scope",
            namespacesField = "lemline_namespaces",
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
    fun `security enabled preserves scope and namespace enforcement`() {
        val authorizer = GatewayAuthorizer(
            authenticationEnabled = true,
            scopeField = "scope",
            namespacesField = "lemline_namespaces",
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
