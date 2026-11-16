// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.config

import com.google.protobuf.Timestamp
import com.lemline.gateway.v1.ConfigFileLocator
import com.lemline.gateway.v1.SettingOrigin
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.ConfigFileLocator as Locator
import com.lemline.runner.config.LemlineConfigConstants
import com.lemline.runner.config.LoadedConfigFile
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.smallrye.config.EnvConfigSource
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import java.nio.file.Path
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("GetGatewayConfig")
class GatewayConfigReportTest {

    private val text = """
        lemline:
          database:
            postgresql:
              password: db-s3cret
          gateway:
            grpc:
              port: 9443
            tls:
              trust-store-password: ts-s3cret
            cors:
              origins: http://localhost:5173
    """.trimIndent() + "\n"

    private val fileProps = mapOf(
        "lemline.database.postgresql.password" to "db-s3cret",
        "lemline.gateway.grpc.port" to "9443",
        "lemline.gateway.tls.trust-store-password" to "ts-s3cret",
        "lemline.gateway.cors.origins" to "http://localhost:5173",
    )

    private val env = mapOf("LEMLINE_GATEWAY_CORS_ORIGINS" to "https://dashboard.example")

    private val config = SmallRyeConfigBuilder()
        .withSources(EnvConfigSource(env, 300))
        .withSources(
            PropertiesConfigSource(
                fileProps + ("lemline.gateway.cors.origins" to "https://dashboard.example"),
                LemlineConfigConstants.CONFIG_SOURCE_NAME,
                LemlineConfigConstants.CONFIG_ORDINAL,
            )
        )
        .build()

    private val snapshot = LoadedConfigFile.Snapshot(Path.of("/etc/lemline/lemline.yaml"), Locator.OPTION, text, fileProps)

    private val response = GatewayConfigReport.build(config, snapshot, Timestamp.getDefaultInstance())

    @Test
    fun `reports each gateway setting with its origin`() {
        val byKey = response.settingsList.associateBy { it.key }
        byKey.getValue("lemline.gateway.grpc.port").let {
            it.value shouldBe "9443"
            it.origin shouldBe SettingOrigin.SETTING_ORIGIN_CONFIG_FILE
            it.originDetail shouldBe "/etc/lemline/lemline.yaml"
            it.defaultValue shouldBe "9000"
        }
        byKey.getValue("lemline.gateway.cors.origins").let {
            it.value shouldBe "https://dashboard.example"
            it.origin shouldBe SettingOrigin.SETTING_ORIGIN_ENVIRONMENT
            it.originDetail shouldBe "LEMLINE_GATEWAY_CORS_ORIGINS"
            it.shadowedList shouldBe listOf(SettingOrigin.SETTING_ORIGIN_CONFIG_FILE)
        }
        byKey.getValue("lemline.gateway.watch.batch-size").origin shouldBe SettingOrigin.SETTING_ORIGIN_DEFAULT
        byKey.getValue("lemline.gateway.authentication.jwt.issuer").origin shouldBe SettingOrigin.SETTING_ORIGIN_UNSET
        response.settingsList.map { it.key } shouldBe response.settingsList.map { it.key }.sorted()
    }

    @Test
    fun `never returns a secret value`() {
        val secret = response.settingsList.single { it.key == "lemline.gateway.tls.trust-store-password" }
        secret.secret shouldBe true
        secret.isSet shouldBe true
        secret.hasValue() shouldBe false

        val serialized = response.toString() + response.toByteString().toStringUtf8()
        serialized shouldNotContain "db-s3cret"
        serialized shouldNotContain "ts-s3cret"
    }

    @Test
    fun `returns the file as written and marks overridden lines`() {
        val file = response.configFile
        file.path shouldBe "/etc/lemline/lemline.yaml"
        file.locatedBy shouldBe ConfigFileLocator.CONFIG_FILE_LOCATOR_OPTION
        file.text shouldContain "origins: http://localhost:5173"
        val origins = file.keysList.single { it.key == "lemline.gateway.cors.origins" }
        origins.line shouldBe 11
        origins.overriddenBy shouldBe SettingOrigin.SETTING_ORIGIN_ENVIRONMENT
        origins.overriddenByDetail shouldBe "LEMLINE_GATEWAY_CORS_ORIGINS"
        file.keysList.single { it.key == "lemline.gateway.grpc.port" }.overriddenBy shouldBe
            SettingOrigin.SETTING_ORIGIN_UNSPECIFIED
    }

    @Test
    fun `no file loaded`() {
        GatewayConfigReport.build(config, null, Timestamp.getDefaultInstance()).hasConfigFile() shouldBe false
    }

    @Test
    fun `requires the config scope when authentication is enabled`() {
        val handler = handler(authenticationEnabled = true)
        val reader = GatewayPrincipal(subject = "ops", scopes = setOf("lemline.watch"), namespaces = setOf("*"))
        assertThrows<GatewayPermissionDeniedException> { handler.getGatewayConfig(reader) }

        val admin = GatewayPrincipal(subject = "ops", scopes = setOf("lemline.config"), namespaces = setOf("*"))
        (handler.getGatewayConfig(admin).settingsCount > 0) shouldBe true
    }

    @Test
    fun `is open when authentication is disabled`() {
        val handler = handler(authenticationEnabled = false)
        val anyone = GatewayPrincipal(subject = null, scopes = emptySet(), namespaces = setOf("*"))
        (handler.getGatewayConfig(anyone).settingsCount > 0) shouldBe true
    }

    private fun handler(authenticationEnabled: Boolean): GatewayConfigRpcHandler {
        val lemline = testLemlineConfiguration(authenticationEnabled = authenticationEnabled)
        val scope = object : NamespaceScope {
            override val configured: Set<String>? = null
        }
        return GatewayConfigRpcHandler(GatewayAuthorizer(lemline, scope))
    }
}
