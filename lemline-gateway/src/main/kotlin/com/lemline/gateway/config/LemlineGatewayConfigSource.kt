// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.config

import com.lemline.gateway.analytics.AnalyticsBackend
import com.lemline.gateway.config.GatewayConfigConstants.ANALYTICS_BACKEND
import com.lemline.gateway.config.GatewayConfigConstants.ANALYTICS_BACKEND_DEFAULT
import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.config.ExtraFileConfigFactory
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_POSTGRES_DATABASE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.POSTGRES_USERNAME_DEFAULT
import io.smallrye.config.PropertiesConfigSource

class LemlineGatewayConfigSource : PropertiesConfigSource(
    buildProperties(),
    GatewayConfigConstants.CONFIG_SOURCE_NAME,
    GatewayConfigConstants.CONFIG_ORDINAL
) {
    companion object {
        private const val LEMLINE_PREFIX = "lemline."

        private fun buildProperties(): Map<String, String> {
            val lemlineProps = mutableMapOf<String, String>()

            ConfigPathHolder.configPath?.let { path ->
                ExtraFileConfigFactory().getConfig(path).properties.forEach { (name, value) ->
                    if (name.startsWith(LEMLINE_PREFIX)) {
                        lemlineProps[name] = value.split("#").first().trim()
                    }
                }
            }

            System.getProperties().forEach { (key, value) ->
                val propertyName = key.toString()
                if (propertyName.startsWith(LEMLINE_PREFIX)) {
                    lemlineProps[propertyName] = value.toString()
                }
            }

            val generatedProps = mutableMapOf<String, String>()
            generatedProps.putAll(generateGatewayGrpcProperties(lemlineProps))
            generatedProps.putAll(generateGatewayJwtProperties(lemlineProps))
            generatedProps.putAll(generateAnalyticsDatasourceProperties(lemlineProps))

            return mutableMapOf<String, String>().apply {
                putAll(lemlineProps)
                putAll(generatedProps)
            }
        }

        private fun generateGatewayGrpcProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            val enabled = props[GatewayConfigConstants.GATEWAY_ENABLED]?.toBooleanStrictOrNull()
                ?: GatewayConfigConstants.GATEWAY_ENABLED_DEFAULT.toBoolean()

            if (!enabled) {
                generated["quarkus.grpc.server.port"] = "0"
                return generated
            }

            generated["quarkus.grpc.server.host"] =
                props[GatewayConfigConstants.GATEWAY_GRPC_HOST] ?: GatewayConfigConstants.GATEWAY_GRPC_HOST_DEFAULT
            generated["quarkus.grpc.server.port"] =
                props[GatewayConfigConstants.GATEWAY_GRPC_PORT] ?: GatewayConfigConstants.GATEWAY_GRPC_PORT_DEFAULT
            generated["quarkus.grpc.server.plain-text"] = "false"
            generated["quarkus.grpc.server.ssl.client-auth"] =
                props[GatewayConfigConstants.GATEWAY_TLS_CLIENT_AUTH] ?: GatewayConfigConstants.GATEWAY_TLS_CLIENT_AUTH_DEFAULT

            props[GatewayConfigConstants.GATEWAY_TLS_CERTIFICATE]?.let {
                generated["quarkus.grpc.server.ssl.certificate"] = it
            }
            props[GatewayConfigConstants.GATEWAY_TLS_PRIVATE_KEY]?.let {
                generated["quarkus.grpc.server.ssl.key"] = it
            }
            props[GatewayConfigConstants.GATEWAY_TLS_TRUST_STORE]?.let {
                generated["quarkus.grpc.server.ssl.trust-store"] = it
            }
            props[GatewayConfigConstants.GATEWAY_TLS_TRUST_STORE_PASSWORD]?.let {
                generated["quarkus.grpc.server.ssl.trust-store-password"] = it
            }

            return generated
        }

        private fun generateGatewayJwtProperties(props: Map<String, String>): Map<String, String> {
            val generated = mutableMapOf<String, String>()
            props[GatewayConfigConstants.GATEWAY_JWT_ISSUER]?.let { generated["mp.jwt.verify.issuer"] = it }
            props[GatewayConfigConstants.GATEWAY_JWT_JWKS_URL]?.let {
                generated["smallrye.jwt.verify.key.location"] = it
            }
            return generated
        }

        private fun generateAnalyticsDatasourceProperties(props: Map<String, String>): Map<String, String> {
            val analytics = "lemline.analytics"
            val analyticsPostgresql = "$analytics.postgresql"
            val analyticsBackendRaw = props[ANALYTICS_BACKEND] ?: ANALYTICS_BACKEND_DEFAULT
            val analyticsBackend = AnalyticsBackend.parse(analyticsBackendRaw)

            if (analyticsBackend == AnalyticsBackend.CLICKHOUSE) {
                return emptyMap()
            }

            val host = props["$analyticsPostgresql.host"] ?: POSTGRES_HOST_DEFAULT
            val port = props["$analyticsPostgresql.port"] ?: POSTGRES_PORT_DEFAULT
            val database = props["$analyticsPostgresql.database"] ?: ANALYTICS_POSTGRES_DATABASE_DEFAULT
            val username = props["$analyticsPostgresql.username"] ?: POSTGRES_USERNAME_DEFAULT
            val password = props["$analyticsPostgresql.password"] ?: POSTGRES_PASSWORD_DEFAULT
            val migrateAtStart = props["$analytics.migrate-at-start"]
                ?: props["$analyticsPostgresql.migrate-at-start"]
                ?: ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
            val baselineOnMigrate = props["$analytics.baseline-on-migrate"]
                ?: props["$analyticsPostgresql.baseline-on-migrate"]
                ?: ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT

            return mapOf(
                "quarkus.datasource.analytics.db-kind" to "postgresql",
                "quarkus.datasource.analytics.username" to username,
                "quarkus.datasource.analytics.password" to password,
                "quarkus.datasource.analytics.jdbc.url" to "jdbc:postgresql://$host:$port/$database",
                "quarkus.flyway.analytics.migrate-at-start" to migrateAtStart,
                "quarkus.flyway.analytics.baseline-on-migrate" to baselineOnMigrate,
                "quarkus.flyway.analytics.locations" to "classpath:db/migration/analytics/postgresql"
            )
        }
    }
}
