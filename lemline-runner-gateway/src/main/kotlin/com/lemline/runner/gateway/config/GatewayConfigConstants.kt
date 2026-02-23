// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

object GatewayConfigConstants {
    const val GATEWAY_ENABLED = "lemline.gateway.enabled"
    const val GATEWAY_ENABLED_DEFAULT = "false"

    const val GATEWAY_GRPC_HOST = "lemline.gateway.grpc.host"
    const val GATEWAY_GRPC_HOST_DEFAULT = "0.0.0.0"
    const val GATEWAY_GRPC_PORT = "lemline.gateway.grpc.port"
    const val GATEWAY_GRPC_PORT_DEFAULT = "9000"

    const val GATEWAY_TLS_ENABLED = "lemline.gateway.tls.enabled"
    const val GATEWAY_TLS_ENABLED_DEFAULT = "true"
    const val GATEWAY_TLS_CERTIFICATE = "lemline.gateway.tls.certificate"
    const val GATEWAY_TLS_PRIVATE_KEY = "lemline.gateway.tls.private-key"
    const val GATEWAY_TLS_TRUST_STORE = "lemline.gateway.tls.trust-store"
    const val GATEWAY_TLS_TRUST_STORE_PASSWORD = "lemline.gateway.tls.trust-store-password"
    const val GATEWAY_TLS_CLIENT_AUTH = "lemline.gateway.tls.client-auth"
    const val GATEWAY_TLS_CLIENT_AUTH_DEFAULT = "none"

    const val GATEWAY_AUTHENTICATION_ENABLED = "lemline.gateway.authentication.enabled"
    const val GATEWAY_AUTHENTICATION_ENABLED_DEFAULT = "true"
    const val GATEWAY_AUTHENTICATION_JWT_ISSUER = "lemline.gateway.authentication.jwt.issuer"
    const val GATEWAY_AUTHENTICATION_JWT_JWKS_URL = "lemline.gateway.authentication.jwt.jwks-url"

    const val GATEWAY_AUTHENTICATION_SCOPE_FIELD = "lemline.gateway.authentication.claims.scope-field"
    const val GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT = "scope"
    const val GATEWAY_AUTHENTICATION_NAMESPACES_FIELD = "lemline.gateway.authentication.claims.namespaces-field"
    const val GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT = "lemline_namespaces"

    const val GATEWAY_CORS_ENABLED = "lemline.gateway.cors.enabled"
    const val GATEWAY_CORS_ENABLED_DEFAULT = "true"
    const val GATEWAY_CORS_ORIGINS = "lemline.gateway.cors.origins"
    const val GATEWAY_CORS_ORIGINS_DEFAULT = "http://localhost:5173"
    const val GATEWAY_CORS_METHODS = "lemline.gateway.cors.methods"
    const val GATEWAY_CORS_METHODS_DEFAULT = "GET,POST,OPTIONS"
    const val GATEWAY_CORS_HEADERS = "lemline.gateway.cors.headers"
    const val GATEWAY_CORS_HEADERS_DEFAULT =
        "Accept,Authorization,Content-Type,Grpc-Timeout,X-Grpc-Web,X-User-Agent"

    const val GATEWAY_WATCH_POLL_INTERVAL_MS = "lemline.gateway.watch.poll-interval-ms"
    const val GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT = "250"
    const val GATEWAY_WATCH_BATCH_SIZE = "lemline.gateway.watch.batch-size"
    const val GATEWAY_WATCH_BATCH_SIZE_DEFAULT = "256"

    const val ANALYTICS_TYPE = "lemline.analytics.type"
    const val ANALYTICS_TYPE_DEFAULT = "postgresql"
    const val ANALYTICS_TYPE_POSTGRESQL = "postgresql"
    const val ANALYTICS_TYPE_CLICKHOUSE = "clickhouse"
}
