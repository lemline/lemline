// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.gateway.config.GatewayConfigConstants
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Profile dedicated to validating strict config mapping coverage for gateway keys.
 */
class GatewayConfigMappingProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            DATABASE_TYPE to DatabaseType.H2.configValue,
            MESSAGING_TYPE to MessagingType.IN_MEMORY.configValue,

            GatewayConfigConstants.GATEWAY_ENABLED to "false",
            GatewayConfigConstants.GATEWAY_GRPC_HOST to "127.0.0.1",
            GatewayConfigConstants.GATEWAY_GRPC_PORT to "9101",
            GatewayConfigConstants.GATEWAY_TLS_ENABLED to "false",
            GatewayConfigConstants.GATEWAY_TLS_CLIENT_AUTH to "request",
            GatewayConfigConstants.GATEWAY_AUTHENTICATION_ENABLED to "false",
            GatewayConfigConstants.GATEWAY_AUTHENTICATION_JWT_ISSUER to "https://issuer.example.test",
            GatewayConfigConstants.GATEWAY_AUTHENTICATION_JWT_JWKS_URL to "https://issuer.example.test/jwks.json",
            GatewayConfigConstants.GATEWAY_AUTHENTICATION_SCOPE_FIELD to "scp",
            GatewayConfigConstants.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD to "tenant_scopes",
            GatewayConfigConstants.GATEWAY_CORS_ENABLED to "true",
            GatewayConfigConstants.GATEWAY_CORS_ORIGINS to "http://localhost:3000",
            GatewayConfigConstants.GATEWAY_CORS_METHODS to "GET,POST",
            GatewayConfigConstants.GATEWAY_CORS_HEADERS to "Authorization,Content-Type",
            GatewayConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS to "777",
            GatewayConfigConstants.GATEWAY_WATCH_BATCH_SIZE to "17",
            GatewayConfigConstants.ANALYTICS_TYPE to GatewayConfigConstants.ANALYTICS_TYPE_CLICKHOUSE,
        )
}
