// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.config

import com.google.protobuf.Timestamp
import com.lemline.gateway.v1.ConfigFile
import com.lemline.gateway.v1.ConfigFileKey
import com.lemline.gateway.v1.ConfigFileLocator as ProtoLocator
import com.lemline.gateway.v1.GatewaySetting
import com.lemline.gateway.v1.GetGatewayConfigResponse
import com.lemline.gateway.v1.SettingOrigin as ProtoOrigin
import com.lemline.runner.config.ConfigFileLocator
import com.lemline.runner.config.ConfigFileRedactor
import com.lemline.runner.config.ConfigMappingKeys
import com.lemline.runner.config.ConfigOriginResolver
import com.lemline.runner.config.DeclaredKey
import com.lemline.runner.config.LoadedConfigFile
import com.lemline.runner.config.ResolvedSetting
import com.lemline.runner.config.SettingOrigin
import io.smallrye.config.SmallRyeConfig

/** Builds the GetGatewayConfig response from the running configuration and the loaded file. */
object GatewayConfigReport {

    fun build(config: SmallRyeConfig, file: LoadedConfigFile.Snapshot?, loadedAt: Timestamp): GetGatewayConfigResponse {
        val resolver = ConfigOriginResolver(config, file?.properties.orEmpty(), file?.path?.toString())
        val response = GetGatewayConfigResponse.newBuilder().setLoadedAt(loadedAt)
        ConfigMappingKeys.gateway.sortedBy { it.key }.forEach { declared ->
            response.addSettings(toProto(resolver.resolve(declared)))
        }
        if (file != null) response.setConfigFile(configFile(file, resolver))
        return response.build()
    }

    private fun toProto(s: ResolvedSetting): GatewaySetting {
        val b = GatewaySetting.newBuilder()
            .setKey(s.key)
            .setOrigin(toProto(s.origin))
            .setOriginDetail(s.originDetail)
            .setSecret(s.secret)
            .setIsSet(s.value != null)
            .addAllShadowed(s.shadowed.map(::toProto))
        // A secret's value never leaves the process, nor does its default.
        if (!s.secret) {
            s.value?.let(b::setValue)
            s.defaultValue?.let(b::setDefaultValue)
        }
        return b.build()
    }

    private fun configFile(file: LoadedConfigFile.Snapshot, resolver: ConfigOriginResolver): ConfigFile {
        val b = ConfigFile.newBuilder()
            .setPath(file.path.toString())
            .setLocatedBy(toProto(file.locator))
        val redacted = file.text?.let(ConfigFileRedactor::redact) ?: return b.build()
        b.setText(redacted.text)
        redacted.keys.forEach { key ->
            val entry = ConfigFileKey.newBuilder().setKey(key.key).setLine(key.line).setSecret(key.secret)
            if (key.key.startsWith("lemline.")) {
                val resolved = resolver.resolve(DeclaredKey(key.key, null))
                val fileValue = file.properties[key.key]
                if (resolved.origin < SettingOrigin.CONFIG_FILE && !sameValue(fileValue, resolved.value)) {
                    entry.setOverriddenBy(toProto(resolved.origin)).setOverriddenByDetail(resolved.originDetail)
                    if (!key.secret && !resolved.secret) resolved.value?.let(entry::setOverridingValue)
                }
            }
            b.addKeys(entry)
        }
        return b.build()
    }

    /** Values equal as configuration: surrounding spaces and boolean case do not matter. */
    private fun sameValue(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val x = a.trim()
        val y = b.trim()
        val booleans = setOf("true", "false")
        return x == y || (x.lowercase() in booleans && x.equals(y, ignoreCase = true))
    }

    private fun toProto(origin: SettingOrigin): ProtoOrigin = when (origin) {
        SettingOrigin.SYSTEM_PROPERTY -> ProtoOrigin.SETTING_ORIGIN_SYSTEM_PROPERTY
        SettingOrigin.COMMAND -> ProtoOrigin.SETTING_ORIGIN_COMMAND
        SettingOrigin.ENVIRONMENT -> ProtoOrigin.SETTING_ORIGIN_ENVIRONMENT
        SettingOrigin.CONFIG_FILE -> ProtoOrigin.SETTING_ORIGIN_CONFIG_FILE
        SettingOrigin.APPLICATION -> ProtoOrigin.SETTING_ORIGIN_APPLICATION
        SettingOrigin.DEFAULT -> ProtoOrigin.SETTING_ORIGIN_DEFAULT
        SettingOrigin.UNSET -> ProtoOrigin.SETTING_ORIGIN_UNSET
    }

    private fun toProto(locator: ConfigFileLocator?): ProtoLocator = when (locator) {
        ConfigFileLocator.OPTION -> ProtoLocator.CONFIG_FILE_LOCATOR_OPTION
        ConfigFileLocator.ENVIRONMENT -> ProtoLocator.CONFIG_FILE_LOCATOR_ENVIRONMENT
        ConfigFileLocator.DEFAULT_PATH -> ProtoLocator.CONFIG_FILE_LOCATOR_DEFAULT_PATH
        null -> ProtoLocator.CONFIG_FILE_LOCATOR_UNSPECIFIED
    }
}
