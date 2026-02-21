// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.config

import com.lemline.gateway.analytics.WorkflowAnalyticsEventSource
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.*
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class GatewayStartupValidator(
    @param:ConfigProperty(
        name = GatewayConfigConstants.GATEWAY_ENABLED,
        defaultValue = GatewayConfigConstants.GATEWAY_ENABLED_DEFAULT
    )
    private val gatewayEnabled: Boolean,
    @param:ConfigProperty(name = GatewayConfigConstants.GATEWAY_TLS_CERTIFICATE)
    private val tlsCertificate: Optional<String>,
    @param:ConfigProperty(name = GatewayConfigConstants.GATEWAY_TLS_PRIVATE_KEY)
    private val tlsPrivateKey: Optional<String>,
    @param:ConfigProperty(name = GatewayConfigConstants.GATEWAY_TLS_TRUST_STORE)
    private val tlsTrustStore: Optional<String>,
    @param:ConfigProperty(name = GatewayConfigConstants.GATEWAY_JWT_ISSUER)
    private val jwtIssuer: Optional<String>,
    @param:ConfigProperty(name = GatewayConfigConstants.GATEWAY_JWT_JWKS_URL)
    private val jwtJwksUrl: Optional<String>,
) {

    @Inject
    lateinit var analyticsEventSource: WorkflowAnalyticsEventSource

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        if (!gatewayEnabled) return

        requireConfigured(tlsCertificate, GatewayConfigConstants.GATEWAY_TLS_CERTIFICATE)
        requireConfigured(tlsPrivateKey, GatewayConfigConstants.GATEWAY_TLS_PRIVATE_KEY)
        requireConfigured(tlsTrustStore, GatewayConfigConstants.GATEWAY_TLS_TRUST_STORE)
        requireConfigured(jwtIssuer, GatewayConfigConstants.GATEWAY_JWT_ISSUER)
        requireConfigured(jwtJwksUrl, GatewayConfigConstants.GATEWAY_JWT_JWKS_URL)

        runBlocking {
            analyticsEventSource.validate()
        }
    }

    private fun requireConfigured(value: Optional<String>, key: String) {
        val configured = value.filter { it.isNotBlank() }.isPresent
        if (!configured) {
            throw IllegalStateException("Missing required gateway configuration: '$key'")
        }
    }
}
