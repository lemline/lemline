// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.runner.common.config.ANALYTICS_BACKEND_CLICKHOUSE
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.tests.profiles.GatewayConfigMappingProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestProfile(GatewayConfigMappingProfile::class)
@QuarkusTest
class GatewayConfigMappingTest {

    @Inject
    lateinit var config: LemlineConfiguration

    @Test
    fun `gateway keys are accepted and mapped`() {
        val gateway = config.gateway().orElseThrow()

        assertFalse(gateway.enabled())
        assertEquals("127.0.0.1", gateway.grpc().orElseThrow().host())
        assertEquals(9101, gateway.grpc().orElseThrow().port())
        assertTrue(gateway.tls().orElseThrow().enabled())
        assertEquals("request", gateway.tls().orElseThrow().clientAuth())

        val auth = gateway.authentication().orElseThrow()
        assertTrue(auth.enabled())
        assertEquals("https://issuer.example.test", auth.jwt().orElseThrow().issuer().orElseThrow())
        assertEquals("https://issuer.example.test/jwks.json", auth.jwt().orElseThrow().jwksUrl().orElseThrow())
        assertEquals("scp", auth.claims().orElseThrow().scopeField())
        assertEquals("tenant_scopes", auth.claims().orElseThrow().namespacesField())

        assertTrue(gateway.cors().orElseThrow().enabled())
        assertEquals("http://localhost:3000", gateway.cors().orElseThrow().origins())
        assertEquals("GET,POST", gateway.cors().orElseThrow().methods())
        assertEquals("Authorization,Content-Type", gateway.cors().orElseThrow().headers())

        assertEquals(777L, gateway.watch().orElseThrow().pollIntervalMs())
        assertEquals(17, gateway.watch().orElseThrow().batchSize())
    }

    @Test
    fun `analytics type key is mapped`() {
        val analytics = config.analytics().orElseThrow()
        assertEquals(ANALYTICS_BACKEND_CLICKHOUSE, analytics.type())
    }
}
