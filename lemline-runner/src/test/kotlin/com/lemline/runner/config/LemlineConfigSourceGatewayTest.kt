// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.common.config.ANALYTICS_BACKEND_CLICKHOUSE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER
import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL
import com.lemline.runner.common.config.LEMLINE_GATEWAY_CORS_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_CLIENT_AUTH
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LemlineConfigSourceGatewayTest {

    @Test
    fun `generates analytics postgres datasource properties when gateway is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
                LEMLINE_ANALYTICS_POSTGRES_PORT to "5544",
                LEMLINE_ANALYTICS_POSTGRES_DATABASE to "analytics",
                LEMLINE_ANALYTICS_POSTGRES_USERNAME to "analytics_user",
                LEMLINE_ANALYTICS_POSTGRES_PASSWORD to "analytics_pass",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("postgresql", source.getValue("quarkus.datasource.analytics.db-kind"))
            assertEquals("analytics_user", source.getValue("quarkus.datasource.analytics.username"))
            assertEquals("analytics_pass", source.getValue("quarkus.datasource.analytics.password"))
            assertEquals(
                "jdbc:postgresql://analytics-db:5544/analytics",
                source.getValue("quarkus.datasource.analytics.jdbc.url")
            )
            assertEquals(
                "classpath:db/migration/analytics/postgresql",
                source.getValue("quarkus.flyway.analytics.locations")
            )
        }
    }

    @Test
    fun `does not generate analytics datasource for clickhouse type when lifecycle consumer is disabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_ANALYTICS_TYPE to ANALYTICS_BACKEND_CLICKHOUSE,
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "false",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
            )
        ) {
            val source = LemlineConfigSource()
            assertNull(source.getValue("quarkus.datasource.analytics.db-kind"))
            assertNull(source.getValue("quarkus.datasource.analytics.jdbc.url"))
            assertNull(source.getValue("quarkus.flyway.analytics.locations"))
        }
    }

    @Test
    fun `still generates analytics datasource for clickhouse type when lifecycle consumer is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_ANALYTICS_TYPE to ANALYTICS_BACKEND_CLICKHOUSE,
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
            )
        ) {
            val source = LemlineConfigSource()
            val jdbcUrl = source.getValue("quarkus.datasource.analytics.jdbc.url")
            assertTrue(
                jdbcUrl.startsWith("jdbc:postgresql://analytics-db:"),
                "Expected analytics JDBC URL to use overridden host, but was: $jdbcUrl"
            )
            assertEquals("postgresql", source.getValue("quarkus.datasource.analytics.db-kind"))
            assertEquals(
                "classpath:db/migration/analytics/postgresql",
                source.getValue("quarkus.flyway.analytics.locations")
            )
        }
    }

    @Test
    fun `fails fast on unsupported analytics type when gateway is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_ANALYTICS_TYPE to "oracle",
            )
        ) {
            assertFailsWith<IllegalStateException> {
                LemlineConfigSource()
            }
        }
    }

    @Test
    fun `enables grpc web and gateway cors defaults when gateway is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("true", source.getValue("quarkus.grpc.server.enable-grpc-web"))
            assertEquals("true", source.getValue("quarkus.http.cors"))
            assertEquals("false", source.getValue("quarkus.grpc.server.plain-text"))
            assertEquals(
                LemlineConfigConstants.GATEWAY_CORS_ORIGINS_DEFAULT,
                source.getValue("quarkus.http.cors.origins")
            )
            assertEquals(
                LemlineConfigConstants.GATEWAY_CORS_METHODS_DEFAULT,
                source.getValue("quarkus.http.cors.methods")
            )
            assertEquals(
                LemlineConfigConstants.GATEWAY_CORS_HEADERS_DEFAULT,
                source.getValue("quarkus.http.cors.headers")
            )
        }
    }

    @Test
    fun `disables cors when gateway cors enabled is false`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_GATEWAY_CORS_ENABLED to "false",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("false", source.getValue("quarkus.http.cors"))
            assertNull(source.getValue("quarkus.http.cors.origins"))
            assertNull(source.getValue("quarkus.http.cors.methods"))
            assertNull(source.getValue("quarkus.http.cors.headers"))
        }
    }

    @Test
    fun `enables plaintext grpc when tls is disabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_GATEWAY_TLS_ENABLED to "false",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("true", source.getValue("quarkus.grpc.server.plain-text"))
            assertEquals("none", source.getValue("quarkus.grpc.server.ssl.client-auth"))
        }
    }

    @Test
    fun `keeps tls client-auth when authentication is disabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_GATEWAY_TLS_ENABLED to "true",
                LEMLINE_GATEWAY_TLS_CLIENT_AUTH to "required",
                LEMLINE_GATEWAY_AUTHENTICATION_ENABLED to "false",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("false", source.getValue("quarkus.grpc.server.plain-text"))
            assertEquals("required", source.getValue("quarkus.grpc.server.ssl.client-auth"))
            assertNull(source.getValue("mp.jwt.verify.issuer"))
            assertNull(source.getValue("smallrye.jwt.verify.key.location"))
        }
    }

    @Test
    fun `generates jwt verification settings when authentication is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_GATEWAY_ENABLED to "true",
                LEMLINE_GATEWAY_AUTHENTICATION_ENABLED to "true",
                LEMLINE_GATEWAY_AUTHENTICATION_JWT_ISSUER to "https://issuer.example.com",
                LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL to
                    "https://issuer.example.com/.well-known/jwks.json",
            )
        ) {
            val source = LemlineConfigSource()
            assertEquals("https://issuer.example.com", source.getValue("mp.jwt.verify.issuer"))
            assertEquals(
                "https://issuer.example.com/.well-known/jwks.json",
                source.getValue("smallrye.jwt.verify.key.location")
            )
        }
    }

    private fun withSystemProperties(
        overrides: Map<String, String>,
        block: () -> Unit
    ) {
        val previousValues = overrides.mapValues { System.getProperty(it.key) }
        val previousConfigPath = ConfigPathHolder.configPath

        try {
            ConfigPathHolder.configPath = null
            overrides.forEach { (key, value) -> System.setProperty(key, value) }
            block()
        } finally {
            overrides.keys.forEach { key ->
                val previous = previousValues[key]
                if (previous == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, previous)
                }
            }
            ConfigPathHolder.configPath = previousConfigPath
        }
    }
}
