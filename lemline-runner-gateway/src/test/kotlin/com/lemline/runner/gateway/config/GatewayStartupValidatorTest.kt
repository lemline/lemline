// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.GATEWAY_TLS_ENABLED
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayStartupValidatorTest {

    @Test
    fun `fails when authentication is enabled and tls is disabled`() {
        val eventSource = mockk<WorkflowAnalyticsEventSource>()
        coEvery { eventSource.validate() } returns Unit

        val validator = GatewayStartupValidator(
            config = TestGatewayRuntimeConfig(
                enabled = true,
                authenticationEnabled = true,
                tlsEnabled = false,
            ),
        ).apply {
            analyticsEventSource = eventSource
        }

        val ex = assertFailsWith<IllegalStateException> {
            validator.onStart(mockk())
        }
        assertTrue(ex.message!!.contains(GATEWAY_AUTHENTICATION_ENABLED))
        assertTrue(ex.message!!.contains(GATEWAY_TLS_ENABLED))
    }

    @Test
    fun `fails when tls is enabled but certificate and key are missing`() {
        val eventSource = mockk<WorkflowAnalyticsEventSource>()
        coEvery { eventSource.validate() } returns Unit

        val validator = GatewayStartupValidator(
            config = TestGatewayRuntimeConfig(
                enabled = true,
                authenticationEnabled = false,
                tlsEnabled = true,
                tlsCertificate = null,
                tlsPrivateKey = null,
            ),
        ).apply {
            analyticsEventSource = eventSource
        }

        assertFailsWith<IllegalStateException> {
            validator.onStart(mockk())
        }
    }

    @Test
    fun `valid config triggers analytics source validation`() {
        val eventSource = mockk<WorkflowAnalyticsEventSource>()
        coEvery { eventSource.validate() } returns Unit

        val validator = GatewayStartupValidator(
            config = TestGatewayRuntimeConfig(
                enabled = true,
                authenticationEnabled = true,
                authenticationJwtIssuer = "https://issuer.example.test",
                authenticationJwtJwksUrl = "https://issuer.example.test/jwks.json",
                tlsEnabled = true,
                tlsCertificate = "/tmp/cert.pem",
                tlsPrivateKey = "/tmp/key.pem",
                tlsClientAuth = "none",
            ),
        ).apply {
            analyticsEventSource = eventSource
        }

        validator.onStart(mockk())
        coVerify(exactly = 1) { eventSource.validate() }
    }
}
