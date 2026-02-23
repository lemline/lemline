// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.GATEWAY_TLS_CERTIFICATE
import com.lemline.runner.common.config.GATEWAY_TLS_ENABLED
import com.lemline.runner.common.config.GATEWAY_TLS_PRIVATE_KEY
import com.lemline.runner.common.config.GATEWAY_TLS_TRUST_STORE
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.*
import kotlinx.coroutines.runBlocking

@ApplicationScoped
class GatewayStartupValidator(
    private val config: GatewayRuntimeConfig,
) {

    @Inject
    lateinit var analyticsEventSource: WorkflowAnalyticsEventSource

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        if (!config.enabled) return

        if (config.authenticationEnabled && !config.tlsEnabled) {
            throw IllegalStateException(
                "Invalid gateway configuration: '$GATEWAY_AUTHENTICATION_ENABLED' " +
                    "requires '$GATEWAY_TLS_ENABLED=true'"
            )
        }

        if (config.tlsEnabled) {
            requireConfigured(config.tlsCertificate, GATEWAY_TLS_CERTIFICATE)
            requireConfigured(config.tlsPrivateKey, GATEWAY_TLS_PRIVATE_KEY)

            val clientAuth = config.tlsClientAuth.trim().lowercase(Locale.ROOT).ifBlank { "none" }

            if (clientAuth == "request" || clientAuth == "required") {
                requireConfigured(config.tlsTrustStore, GATEWAY_TLS_TRUST_STORE)
            }
        }

        if (config.authenticationEnabled) {
            requireConfigured(config.authenticationJwtIssuer, GATEWAY_AUTHENTICATION_JWT_ISSUER)
            requireConfigured(config.authenticationJwtJwksUrl, GATEWAY_AUTHENTICATION_JWT_JWKS_URL)
        }

        runBlocking {
            analyticsEventSource.validate()
        }
    }

    private fun requireConfigured(value: String?, key: String) {
        val configured = value?.isNotBlank() == true
        if (!configured) {
            throw IllegalStateException("Missing required gateway configuration: '$key'")
        }
    }
}
