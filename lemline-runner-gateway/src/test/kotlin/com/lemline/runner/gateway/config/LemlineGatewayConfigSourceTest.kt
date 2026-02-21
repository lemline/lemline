// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LemlineGatewayConfigSourceTest {

    @Test
    fun `generates analytics postgres datasource properties by default`() {
        withSystemProperties(
            mapOf(
                GatewayConfigConstants.GATEWAY_ENABLED to "true",
                "lemline.analytics.postgresql.host" to "analytics-db",
                "lemline.analytics.postgresql.port" to "5544",
                "lemline.analytics.postgresql.database" to "analytics",
                "lemline.analytics.postgresql.username" to "analytics_user",
                "lemline.analytics.postgresql.password" to "analytics_pass",
            )
        ) {
            val source = LemlineGatewayConfigSource()
            assertEquals("postgresql", source.getValue("quarkus.datasource.analytics.db-kind"))
            assertEquals("analytics_user", source.getValue("quarkus.datasource.analytics.username"))
            assertEquals("analytics_pass", source.getValue("quarkus.datasource.analytics.password"))
            assertEquals("jdbc:postgresql://analytics-db:5544/analytics", source.getValue("quarkus.datasource.analytics.jdbc.url"))
            assertEquals(
                "classpath:db/migration/analytics/postgresql",
                source.getValue("quarkus.flyway.analytics.locations")
            )
        }
    }

    @Test
    fun `does not generate analytics datasource for clickhouse backend`() {
        withSystemProperties(
            mapOf(
                GatewayConfigConstants.GATEWAY_ENABLED to "true",
                GatewayConfigConstants.ANALYTICS_BACKEND to GatewayConfigConstants.ANALYTICS_BACKEND_CLICKHOUSE,
                "lemline.analytics.postgresql.host" to "analytics-db",
            )
        ) {
            val source = LemlineGatewayConfigSource()
            assertNull(source.getValue("quarkus.datasource.analytics.db-kind"))
            assertNull(source.getValue("quarkus.datasource.analytics.jdbc.url"))
            assertNull(source.getValue("quarkus.flyway.analytics.locations"))
        }
    }

    @Test
    fun `fails fast on unsupported analytics backend`() {
        withSystemProperties(
            mapOf(
                GatewayConfigConstants.GATEWAY_ENABLED to "true",
                GatewayConfigConstants.ANALYTICS_BACKEND to "oracle",
            )
        ) {
            assertFailsWith<IllegalStateException> {
                LemlineGatewayConfigSource()
            }
        }
    }

    private fun withSystemProperties(
        overrides: Map<String, String>,
        block: () -> Unit
    ) {
        val previousValues = overrides.mapValues { System.getProperty(it.key) }
        val previousConfigPath = System.getProperty("lemline.config.path")

        try {
            System.clearProperty("lemline.config.path")
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
            if (previousConfigPath == null) {
                System.clearProperty("lemline.config.path")
            } else {
                System.setProperty("lemline.config.path", previousConfigPath)
            }
        }
    }
}
