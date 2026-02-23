// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

/**
 * Resolved runtime configuration required by gateway components.
 *
 * This is produced from the global Lemline configuration mapping in the runner module.
 */
interface GatewayRuntimeConfig {
    val enabled: Boolean

    val authenticationEnabled: Boolean
    val authenticationScopeField: String
    val authenticationNamespacesField: String
    val authenticationJwtIssuer: String?
    val authenticationJwtJwksUrl: String?

    val tlsEnabled: Boolean
    val tlsClientAuth: String
    val tlsCertificate: String?
    val tlsPrivateKey: String?
    val tlsTrustStore: String?
    val tlsTrustStorePassword: String?

    val watchPollIntervalMs: Long
    val watchBatchSize: Int

    val analyticsType: String
    val analyticsSchema: String
    val analyticsTable: String
}
