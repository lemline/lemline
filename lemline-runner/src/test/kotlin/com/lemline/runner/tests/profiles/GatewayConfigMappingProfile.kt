// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.ANALYTICS_TYPE_H2
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_CLAIMS_NAMESPACES_FIELD
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_CLAIMS_SCOPE_FIELD
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_HEADERS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_METHODS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_ORIGINS
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_GRPC_HOST
import com.lemline.runner.common.config.LEMLINE_GATEWAY_GRPC_PORT
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_CLIENT_AUTH
import com.lemline.runner.common.config.LEMLINE_GATEWAY_WATCH_BATCH_SIZE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_WATCH_POLL_INTERVAL_MS
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.MessagingType
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Profile dedicated to validating strict config mapping coverage for gateway keys.
 */
class GatewayConfigMappingProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            LEMLINE_MESSAGING_TYPE to MessagingType.IN_MEMORY.configValue,

            LEMLINE_GATEWAY_ENABLED to "false",
            LEMLINE_GATEWAY_GRPC_HOST to "127.0.0.1",
            LEMLINE_GATEWAY_GRPC_PORT to "9101",
            LEMLINE_GATEWAY_TLS_CLIENT_AUTH to "request",
            LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER to "https://issuer.example.test",
            LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL to "https://issuer.example.test/jwks.json",
            LEMLINE_GATEWAY_AUTHENTICATION_CLAIMS_SCOPE_FIELD to "scp",
            LEMLINE_GATEWAY_AUTHENTICATION_CLAIMS_NAMESPACES_FIELD to "tenant_scopes",
            LEMLINE_GATEWAY_CORS_ENABLED to "true",
            LEMLINE_GATEWAY_CORS_ORIGINS to "http://localhost:3000",
            LEMLINE_GATEWAY_CORS_METHODS to "GET,POST",
            LEMLINE_GATEWAY_CORS_HEADERS to "Authorization,Content-Type",
            LEMLINE_GATEWAY_WATCH_POLL_INTERVAL_MS to "777",
            LEMLINE_GATEWAY_WATCH_BATCH_SIZE to "17",
            LEMLINE_ANALYTICS_TYPE to ANALYTICS_TYPE_H2,
        )
}
