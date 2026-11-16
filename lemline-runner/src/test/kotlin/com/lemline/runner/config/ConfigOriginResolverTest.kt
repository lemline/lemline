// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.SysPropConfigSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigOriginResolverTest {

    private val filePath = "/etc/lemline/lemline.yaml"
    private val fileProps = mapOf(
        "lemline.gateway.grpc.port" to "9443",
        "lemline.gateway.cors.origins" to "http://localhost:5173",
    )

    @AfterTest
    fun clearSystemProperties() {
        System.clearProperty("lemline.gateway.authentication.enabled")
    }

    /** Mirrors the runtime: the merged Lemline source holds file values plus higher-priority values. */
    private fun config(env: Map<String, String> = emptyMap(), application: Map<String, String> = emptyMap()): SmallRyeConfig {
        val merged = application + fileProps + env.mapKeys { (k, _) -> k.lowercase().replace('_', '.') }
        return SmallRyeConfigBuilder()
            .withSources(SysPropConfigSource())
            .withSources(EnvConfigSource(env, 300))
            .withSources(PropertiesConfigSource(merged, LemlineConfigConstants.CONFIG_SOURCE_NAME, LemlineConfigConstants.CONFIG_ORDINAL))
            .withSources(PropertiesConfigSource(application, "PropertiesConfigSource[source=application.properties]", 250))
            .build()
    }

    private fun resolver(config: SmallRyeConfig) = ConfigOriginResolver(config, fileProps, filePath)

    @Test
    fun `value from the configuration file`() {
        val s = resolver(config()).resolve(DeclaredKey("lemline.gateway.grpc.port", "9000"))
        assertEquals("9443", s.value)
        assertEquals(SettingOrigin.CONFIG_FILE, s.origin)
        assertEquals(filePath, s.originDetail)
        assertEquals("9000", s.defaultValue)
        assertTrue(s.shadowed.isEmpty())
    }

    @Test
    fun `environment variable overrides the file`() {
        val env = mapOf("LEMLINE_GATEWAY_CORS_ORIGINS" to "https://dashboard.example")
        val s = resolver(config(env = env)).resolve(DeclaredKey("lemline.gateway.cors.origins", "http://localhost:5173"))
        assertEquals("https://dashboard.example", s.value)
        assertEquals(SettingOrigin.ENVIRONMENT, s.origin)
        assertEquals("LEMLINE_GATEWAY_CORS_ORIGINS", s.originDetail)
        assertEquals(listOf(SettingOrigin.CONFIG_FILE), s.shadowed)
    }

    @Test
    fun `system property wins over everything`() {
        System.setProperty("lemline.gateway.authentication.enabled", "false")
        val s = resolver(config()).resolve(DeclaredKey("lemline.gateway.authentication.enabled", "true"))
        assertEquals("false", s.value)
        assertEquals(SettingOrigin.SYSTEM_PROPERTY, s.origin)
        assertEquals("lemline.gateway.authentication.enabled", s.originDetail)
    }

    @Test
    fun `value from the packaged application file`() {
        val s = resolver(config(application = mapOf("lemline.gateway.watch.batch-size" to "64")))
            .resolve(DeclaredKey("lemline.gateway.watch.batch-size", "256"))
        assertEquals("64", s.value)
        assertEquals(SettingOrigin.APPLICATION, s.origin)
        assertEquals("", s.originDetail)
    }

    @Test
    fun `built-in default`() {
        val s = resolver(config()).resolve(DeclaredKey("lemline.gateway.watch.poll-interval-ms", "250"))
        assertEquals("250", s.value)
        assertEquals(SettingOrigin.DEFAULT, s.origin)
    }

    @Test
    fun `unset key without default`() {
        val s = resolver(config()).resolve(DeclaredKey("lemline.gateway.authentication.jwt.issuer", null))
        assertNull(s.value)
        assertEquals(SettingOrigin.UNSET, s.origin)
    }

    @Test
    fun `secret keys are flagged`() {
        val s = resolver(config()).resolve(DeclaredKey("lemline.gateway.tls.trust-store-password", null))
        assertTrue(s.secret)
        assertFalse(resolver(config()).resolve(DeclaredKey("lemline.gateway.tls.private-key", null)).secret)
    }

    @Test
    fun `gateway keys cover every leaf of the gateway mapping`() {
        val keys = ConfigMappingKeys.gateway.associate { it.key to it.defaultValue }
        assertEquals(keys.size, ConfigMappingKeys.gateway.size, "no duplicate key")
        listOf(
            "lemline.gateway.enabled" to "false",
            "lemline.gateway.grpc.host" to "0.0.0.0",
            "lemline.gateway.grpc.port" to "9000",
            "lemline.gateway.tls.private-key" to null,
            "lemline.gateway.tls.trust-store-password" to null,
            "lemline.gateway.tls.client-auth" to "none",
            "lemline.gateway.authentication.jwt.jwks-url" to null,
            "lemline.gateway.authentication.claims.namespaces-field" to "lemline_namespaces",
            "lemline.gateway.cors.origins" to "http://localhost:5173",
            "lemline.gateway.watch.poll-interval-ms" to "250",
            "lemline.namespaces" to null,
        ).forEach { (key, default) ->
            assertTrue(key in keys, "missing $key")
            assertEquals(default, keys[key], "default of $key")
        }
        // Every leaf method of the mapping, counted independently of the walker.
        fun leaves(type: Class<*>): Int = type.methods.filter { it.declaringClass == type && it.parameterCount == 0 }
            .sumOf { m ->
                val generic = m.genericReturnType as? java.lang.reflect.ParameterizedType
                val target = if (m.returnType == java.util.Optional::class.java) generic?.actualTypeArguments?.first() as? Class<*> else m.returnType
                if (target != null && target.isInterface && target.enclosingClass == LemlineConfiguration::class.java) leaves(target) else 1
            }
        assertEquals(leaves(LemlineConfiguration.GatewayConfig::class.java) + 1, keys.size)
    }
}
