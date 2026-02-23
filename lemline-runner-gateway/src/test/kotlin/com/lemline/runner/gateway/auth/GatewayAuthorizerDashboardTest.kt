// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.gateway.config.TestGatewayRuntimeConfig
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GatewayAuthorizerDashboardTest {

    @Test
    fun `security disabled allows all scope and namespace checks`() {
        val authorizer = GatewayAuthorizer(
            config = TestGatewayRuntimeConfig(
                authenticationEnabled = false,
                authenticationScopeField = "scope",
                authenticationNamespacesField = "lemline_namespaces",
            ),
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
            config = TestGatewayRuntimeConfig(
                authenticationEnabled = true,
                authenticationScopeField = "scope",
                authenticationNamespacesField = "lemline_namespaces",
            ),
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
