// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_ENABLED
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class GatewayStartupValidatorTest {

    @Test
    fun `analytics validation preserves startup classloader across dispatcher switches`() {
        val startupThread = Thread.currentThread()
        val originalLoader = startupThread.contextClassLoader
        val applicationLoader = object : ClassLoader(originalLoader) {}
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "gateway-validation-test").apply { contextClassLoader = originalLoader }
        }.asCoroutineDispatcher().use { dispatcher ->
            val eventSource = mockk<WorkflowAnalyticsEventSource>()
            coEvery { eventSource.validate() } coAnswers {
                withContext(dispatcher) {
                    assertSame(applicationLoader, Thread.currentThread().contextClassLoader)
                    delay(1)
                    assertSame(applicationLoader, Thread.currentThread().contextClassLoader)
                }
            }
            val validator = GatewayStartupValidator(
                config = testLemlineConfiguration(
                    gatewayEnabled = true,
                    authenticationEnabled = false,
                    tlsEnabled = false,
                ),
            ).apply {
                analyticsEventSource = eventSource
            }
            val event = mockk<io.quarkus.runtime.StartupEvent>()

            try {
                startupThread.contextClassLoader = applicationLoader
                validator.onStart(event)
                assertSame(applicationLoader, startupThread.contextClassLoader)
                coVerify(exactly = 1) { eventSource.validate() }
            } finally {
                startupThread.contextClassLoader = originalLoader
            }
        }
    }

    @Test
    fun `fails when authentication is enabled and tls is disabled`() {
        val eventSource = mockk<WorkflowAnalyticsEventSource>()
        coEvery { eventSource.validate() } returns Unit

        val validator = GatewayStartupValidator(
            config = testLemlineConfiguration(
                gatewayEnabled = true,
                authenticationEnabled = true,
                tlsEnabled = false,
            ),
        ).apply {
            analyticsEventSource = eventSource
        }

        val ex = assertFailsWith<IllegalStateException> {
            validator.onStart(mockk())
        }
        assertTrue(ex.message!!.contains(LEMLINE_GATEWAY_AUTHENTICATION_ENABLED))
        assertTrue(ex.message!!.contains(LEMLINE_GATEWAY_TLS_ENABLED))
    }

    @Test
    fun `fails when tls is enabled but certificate and key are missing`() {
        val eventSource = mockk<WorkflowAnalyticsEventSource>()
        coEvery { eventSource.validate() } returns Unit

        val validator = GatewayStartupValidator(
            config = testLemlineConfiguration(
                gatewayEnabled = true,
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
            config = testLemlineConfiguration(
                gatewayEnabled = true,
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
