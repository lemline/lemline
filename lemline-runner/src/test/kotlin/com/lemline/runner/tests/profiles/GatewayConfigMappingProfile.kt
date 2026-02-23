// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.ANALYTICS_BACKEND_CLICKHOUSE
import com.lemline.runner.common.config.ANALYTICS_TYPE
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD
import com.lemline.runner.common.config.GATEWAY_AUTHENTICATION_SCOPE_FIELD
import com.lemline.runner.common.config.GATEWAY_CORS_ENABLED
import com.lemline.runner.common.config.GATEWAY_CORS_HEADERS
import com.lemline.runner.common.config.GATEWAY_CORS_METHODS
import com.lemline.runner.common.config.GATEWAY_CORS_ORIGINS
import com.lemline.runner.common.config.GATEWAY_ENABLED
import com.lemline.runner.common.config.GATEWAY_GRPC_HOST
import com.lemline.runner.common.config.GATEWAY_GRPC_PORT
import com.lemline.runner.common.config.GATEWAY_TLS_CLIENT_AUTH
import com.lemline.runner.common.config.GATEWAY_WATCH_BATCH_SIZE
import com.lemline.runner.common.config.GATEWAY_WATCH_POLL_INTERVAL_MS
import com.lemline.runner.common.config.MessagingType
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Profile dedicated to validating strict config mapping coverage for gateway keys.
 */
class GatewayConfigMappingProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "lemline.database.type" to DatabaseType.H2.configValue,
            "lemline.messaging.type" to MessagingType.IN_MEMORY.configValue,

            GATEWAY_ENABLED to "false",
            GATEWAY_GRPC_HOST to "127.0.0.1",
            GATEWAY_GRPC_PORT to "9101",
            GATEWAY_TLS_CLIENT_AUTH to "request",
            GATEWAY_AUTHENTICATION_JWT_ISSUER to "https://issuer.example.test",
            GATEWAY_AUTHENTICATION_JWT_JWKS_URL to "https://issuer.example.test/jwks.json",
            GATEWAY_AUTHENTICATION_SCOPE_FIELD to "scp",
            GATEWAY_AUTHENTICATION_NAMESPACES_FIELD to "tenant_scopes",
            GATEWAY_CORS_ENABLED to "true",
            GATEWAY_CORS_ORIGINS to "http://localhost:3000",
            GATEWAY_CORS_METHODS to "GET,POST",
            GATEWAY_CORS_HEADERS to "Authorization,Content-Type",
            GATEWAY_WATCH_POLL_INTERVAL_MS to "777",
            GATEWAY_WATCH_BATCH_SIZE to "17",
            ANALYTICS_TYPE to ANALYTICS_BACKEND_CLICKHOUSE,
        )
}
