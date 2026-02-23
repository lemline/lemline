// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.common.config.ANALYTICS_TYPE_DEFAULT
import kotlin.jvm.optionals.getOrNull

val LemlineConfiguration.gatewayEnabled: Boolean
    get() = gateway().getOrNull()?.enabled() ?: LemlineConfigConstants.GATEWAY_ENABLED_DEFAULT.toBoolean()

val LemlineConfiguration.gatewayAuthenticationEnabled: Boolean
    get() = gateway().getOrNull()?.authentication()?.getOrNull()?.enabled()
        ?: LemlineConfigConstants.GATEWAY_AUTHENTICATION_ENABLED_DEFAULT.toBoolean()

val LemlineConfiguration.gatewayAuthenticationScopeField: String
    get() = gateway().getOrNull()?.authentication()?.getOrNull()?.claims()?.getOrNull()?.scopeField()
        ?: LemlineConfigConstants.GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT

val LemlineConfiguration.gatewayAuthenticationNamespacesField: String
    get() = gateway().getOrNull()?.authentication()?.getOrNull()?.claims()?.getOrNull()?.namespacesField()
        ?: LemlineConfigConstants.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT

val LemlineConfiguration.gatewayAuthenticationJwtIssuer: String?
    get() = gateway().getOrNull()?.authentication()?.getOrNull()?.jwt()?.getOrNull()?.issuer()?.getOrNull()

val LemlineConfiguration.gatewayAuthenticationJwtJwksUrl: String?
    get() = gateway().getOrNull()?.authentication()?.getOrNull()?.jwt()?.getOrNull()?.jwksUrl()?.getOrNull()

val LemlineConfiguration.gatewayTlsEnabled: Boolean
    get() = gateway().getOrNull()?.tls()?.getOrNull()?.enabled()
        ?: LemlineConfigConstants.GATEWAY_TLS_ENABLED_DEFAULT.toBoolean()

val LemlineConfiguration.gatewayTlsClientAuth: String
    get() = gateway().getOrNull()?.tls()?.getOrNull()?.clientAuth()
        ?: LemlineConfigConstants.GATEWAY_TLS_CLIENT_AUTH_DEFAULT

val LemlineConfiguration.gatewayTlsCertificate: String?
    get() = gateway().getOrNull()?.tls()?.getOrNull()?.certificate()?.getOrNull()

val LemlineConfiguration.gatewayTlsPrivateKey: String?
    get() = gateway().getOrNull()?.tls()?.getOrNull()?.privateKey()?.getOrNull()

val LemlineConfiguration.gatewayTlsTrustStore: String?
    get() = gateway().getOrNull()?.tls()?.getOrNull()?.trustStore()?.getOrNull()

val LemlineConfiguration.gatewayWatchPollIntervalMs: Long
    get() = gateway().getOrNull()?.watch()?.getOrNull()?.pollIntervalMs()
        ?: LemlineConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT.toLong()

val LemlineConfiguration.gatewayWatchBatchSize: Int
    get() = gateway().getOrNull()?.watch()?.getOrNull()?.batchSize()
        ?: LemlineConfigConstants.GATEWAY_WATCH_BATCH_SIZE_DEFAULT.toInt()

val LemlineConfiguration.analyticsTypeResolved: String
    get() = analytics().getOrNull()?.type() ?: ANALYTICS_TYPE_DEFAULT

val LemlineConfiguration.analyticsSchemaResolved: String
    get() = analytics().getOrNull()?.postgresql()?.schema() ?: LemlineConfigConstants.ANALYTICS_POSTGRES_SCHEMA_DEFAULT

val LemlineConfiguration.analyticsTableResolved: String
    get() = analytics().getOrNull()?.postgresql()?.table() ?: LemlineConfigConstants.ANALYTICS_POSTGRES_TABLE_DEFAULT
