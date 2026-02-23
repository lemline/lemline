// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import com.lemline.runner.common.config.ANALYTICS_BACKEND_POSTGRESQL

internal data class TestGatewayRuntimeConfig(
    override val enabled: Boolean = false,
    override val authenticationEnabled: Boolean = true,
    override val authenticationScopeField: String = "scope",
    override val authenticationNamespacesField: String = "lemline_namespaces",
    override val authenticationJwtIssuer: String? = null,
    override val authenticationJwtJwksUrl: String? = null,
    override val tlsEnabled: Boolean = true,
    override val tlsClientAuth: String = "none",
    override val tlsCertificate: String? = null,
    override val tlsPrivateKey: String? = null,
    override val tlsTrustStore: String? = null,
    override val tlsTrustStorePassword: String? = null,
    override val watchPollIntervalMs: Long = 250L,
    override val watchBatchSize: Int = 256,
    override val analyticsType: String = ANALYTICS_BACKEND_POSTGRESQL,
    override val analyticsSchema: String = "public",
    override val analyticsTable: String = "lemline_lifecycle_events",
) : GatewayRuntimeConfig
