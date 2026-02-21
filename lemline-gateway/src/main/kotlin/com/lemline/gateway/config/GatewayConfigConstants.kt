// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.config

object GatewayConfigConstants {
    const val CONFIG_SOURCE_NAME = "LemlineGatewayConfigSource"
    const val CONFIG_ORDINAL = 276

    const val GATEWAY_ENABLED = "lemline.gateway.enabled"
    const val GATEWAY_ENABLED_DEFAULT = "true"

    const val GATEWAY_GRPC_HOST = "lemline.gateway.grpc.host"
    const val GATEWAY_GRPC_HOST_DEFAULT = "0.0.0.0"
    const val GATEWAY_GRPC_PORT = "lemline.gateway.grpc.port"
    const val GATEWAY_GRPC_PORT_DEFAULT = "9000"

    const val GATEWAY_TLS_CERTIFICATE = "lemline.gateway.grpc.tls.certificate"
    const val GATEWAY_TLS_PRIVATE_KEY = "lemline.gateway.grpc.tls.private-key"
    const val GATEWAY_TLS_TRUST_STORE = "lemline.gateway.grpc.tls.trust-store"
    const val GATEWAY_TLS_TRUST_STORE_PASSWORD = "lemline.gateway.grpc.tls.trust-store-password"
    const val GATEWAY_TLS_CLIENT_AUTH = "lemline.gateway.grpc.tls.client-auth"
    const val GATEWAY_TLS_CLIENT_AUTH_DEFAULT = "required"

    const val GATEWAY_JWT_ISSUER = "lemline.gateway.security.jwt.issuer"
    const val GATEWAY_JWT_JWKS_URL = "lemline.gateway.security.jwt.jwks-url"

    const val GATEWAY_SCOPE_FIELD = "lemline.gateway.security.claims.scope-field"
    const val GATEWAY_SCOPE_FIELD_DEFAULT = "scope"
    const val GATEWAY_NAMESPACES_FIELD = "lemline.gateway.security.claims.namespaces-field"
    const val GATEWAY_NAMESPACES_FIELD_DEFAULT = "lemline_namespaces"

    const val GATEWAY_WATCH_POLL_INTERVAL_MS = "lemline.gateway.watch.poll-interval-ms"
    const val GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT = "250"
    const val GATEWAY_WATCH_BATCH_SIZE = "lemline.gateway.watch.batch-size"
    const val GATEWAY_WATCH_BATCH_SIZE_DEFAULT = "256"

    const val ANALYTICS_BACKEND = "lemline.analytics.backend"
    const val ANALYTICS_BACKEND_DEFAULT = "postgresql"
    const val ANALYTICS_BACKEND_POSTGRESQL = "postgresql"
    const val ANALYTICS_BACKEND_CLICKHOUSE = "clickhouse"
}
