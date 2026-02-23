// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.gateway.config.GatewayRuntimeConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject

@ApplicationScoped
class GatewayRuntimeConfigProducer {

    @Inject
    lateinit var config: LemlineConfiguration

    @Produces
    @ApplicationScoped
    fun gatewayRuntimeConfig(): GatewayRuntimeConfig = object : GatewayRuntimeConfig {
        override val enabled: Boolean get() = config.gatewayEnabled

        override val authenticationEnabled: Boolean get() = config.gatewayAuthenticationEnabled
        override val authenticationScopeField: String get() = config.gatewayAuthenticationScopeField
        override val authenticationNamespacesField: String get() = config.gatewayAuthenticationNamespacesField
        override val authenticationJwtIssuer: String? get() = config.gatewayAuthenticationJwtIssuer
        override val authenticationJwtJwksUrl: String? get() = config.gatewayAuthenticationJwtJwksUrl

        override val tlsEnabled: Boolean get() = config.gatewayTlsEnabled
        override val tlsClientAuth: String get() = config.gatewayTlsClientAuth
        override val tlsCertificate: String? get() = config.gatewayTlsCertificate
        override val tlsPrivateKey: String? get() = config.gatewayTlsPrivateKey
        override val tlsTrustStore: String? get() = config.gatewayTlsTrustStore
        override val tlsTrustStorePassword: String? get() = config.gatewayTlsTrustStorePassword

        override val watchPollIntervalMs: Long get() = config.gatewayWatchPollIntervalMs
        override val watchBatchSize: Int get() = config.gatewayWatchBatchSize

        override val analyticsType: String get() = config.analyticsTypeResolved
        override val analyticsSchema: String get() = config.analyticsSchemaResolved
        override val analyticsTable: String get() = config.analyticsTableResolved
    }
}
