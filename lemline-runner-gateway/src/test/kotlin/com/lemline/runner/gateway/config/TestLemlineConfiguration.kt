// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import com.lemline.runner.common.config.ANALYTICS_BACKEND_POSTGRESQL
import com.lemline.runner.config.LemlineConfiguration
import io.mockk.every
import io.mockk.mockk
import java.util.*

internal fun testLemlineConfiguration(
    gatewayEnabled: Boolean = false,
    authenticationEnabled: Boolean = true,
    authenticationScopeField: String = "scope",
    authenticationNamespacesField: String = "lemline_namespaces",
    authenticationJwtIssuer: String? = null,
    authenticationJwtJwksUrl: String? = null,
    tlsEnabled: Boolean = true,
    tlsClientAuth: String = "none",
    tlsCertificate: String? = null,
    tlsPrivateKey: String? = null,
    tlsTrustStore: String? = null,
    tlsTrustStorePassword: String? = null,
    watchPollIntervalMs: Long = 250L,
    watchBatchSize: Int = 256,
    analyticsType: String = ANALYTICS_BACKEND_POSTGRESQL,
    analyticsSchema: String = "public",
    analyticsTable: String = "lemline_lifecycle_events",
): LemlineConfiguration {
    val config = mockk<LemlineConfiguration>()

    val gateway = mockk<LemlineConfiguration.GatewayConfig>()
    val auth = mockk<LemlineConfiguration.GatewayAuthenticationConfig>()
    val claims = mockk<LemlineConfiguration.GatewayClaimsConfig>()
    val jwt = mockk<LemlineConfiguration.GatewayJwtConfig>()
    val tls = mockk<LemlineConfiguration.GatewayTlsConfig>()
    val watch = mockk<LemlineConfiguration.GatewayWatchConfig>()

    every { gateway.enabled() } returns gatewayEnabled
    every { gateway.authentication() } returns Optional.of(auth)
    every { gateway.tls() } returns Optional.of(tls)
    every { gateway.watch() } returns Optional.of(watch)

    every { auth.enabled() } returns authenticationEnabled
    every { auth.claims() } returns Optional.of(claims)
    every { auth.jwt() } returns Optional.of(jwt)

    every { claims.scopeField() } returns authenticationScopeField
    every { claims.namespacesField() } returns authenticationNamespacesField

    every { jwt.issuer() } returns Optional.ofNullable(authenticationJwtIssuer)
    every { jwt.jwksUrl() } returns Optional.ofNullable(authenticationJwtJwksUrl)

    every { tls.enabled() } returns tlsEnabled
    every { tls.clientAuth() } returns tlsClientAuth
    every { tls.certificate() } returns Optional.ofNullable(tlsCertificate)
    every { tls.privateKey() } returns Optional.ofNullable(tlsPrivateKey)
    every { tls.trustStore() } returns Optional.ofNullable(tlsTrustStore)
    every { tls.trustStorePassword() } returns Optional.ofNullable(tlsTrustStorePassword)

    every { watch.pollIntervalMs() } returns watchPollIntervalMs
    every { watch.batchSize() } returns watchBatchSize

    val analytics = mockk<LemlineConfiguration.AnalyticsConfig>()
    val analyticsPostgresql = mockk<LemlineConfiguration.AnalyticsPostgreSQLConfig>()

    every { analytics.type() } returns analyticsType
    every { analytics.postgresql() } returns analyticsPostgresql
    every { analyticsPostgresql.schema() } returns analyticsSchema
    every { analyticsPostgresql.table() } returns analyticsTable

    every { config.gateway() } returns Optional.of(gateway)
    every { config.analytics() } returns Optional.of(analytics)

    return config
}
