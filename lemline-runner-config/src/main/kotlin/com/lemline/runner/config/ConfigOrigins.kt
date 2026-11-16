// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SysPropConfigSource
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.Optional
import org.eclipse.microprofile.config.spi.ConfigSource

/** Where an effective configuration value comes from, highest priority first. */
enum class SettingOrigin {
    SYSTEM_PROPERTY,
    ENVIRONMENT,
    CONFIG_FILE,
    APPLICATION,
    DEFAULT,
    UNSET,
}

/** One configuration key, resolved against the running configuration. */
data class ResolvedSetting(
    val key: String,
    val value: String?,
    val origin: SettingOrigin,
    /** Environment variable, `-D` property or file path to look for; empty for defaults and unset keys. */
    val originDetail: String,
    val defaultValue: String?,
    val secret: Boolean,
    /** Lower-priority origins that also set this key and are hidden by [origin]. */
    val shadowed: List<SettingOrigin>,
)

/** A configuration key declared by a `@ConfigMapping` interface, with its built-in default. */
data class DeclaredKey(val key: String, val defaultValue: String?)

/**
 * Tells, for each key, which origin provides its effective value.
 *
 * File values cannot be told apart from SmallRye alone: [LemlineConfigSource] merges the external file with
 * the packaged application files into one source. The values read from the file ([fileProperties]) settle it.
 */
class ConfigOriginResolver(
    private val config: SmallRyeConfig,
    private val fileProperties: Map<String, String>,
    private val filePath: String?,
) {
    fun resolve(declared: DeclaredKey): ResolvedSetting {
        val key = declared.key
        val current = config.getConfigValue(key)
        val value = current?.value
        var origin = if (value == null) SettingOrigin.UNSET else classify(key, current.sourceName, current.sourceOrdinal)
        var effective = value
        if (origin == SettingOrigin.UNSET && declared.defaultValue != null) {
            origin = SettingOrigin.DEFAULT
            effective = declared.defaultValue
        }
        val shadowed = if (value == null) emptyList() else shadowedBy(key, current.sourceOrdinal, origin)
        return ResolvedSetting(
            key = key,
            value = effective,
            origin = origin,
            originDetail = detail(key, origin),
            defaultValue = declared.defaultValue,
            secret = ConfigFileRedactor.isSecret(key),
            shadowed = shadowed,
        )
    }

    private fun classify(key: String, sourceName: String?, ordinal: Int): SettingOrigin {
        val source = config.configSources.firstOrNull { it.name == sourceName }
        return when {
            source is SysPropConfigSource -> SettingOrigin.SYSTEM_PROPERTY
            source is EnvConfigSource -> SettingOrigin.ENVIRONMENT
            ordinal <= 0 -> SettingOrigin.DEFAULT
            source != null && source.isLemlineSource() && key in fileProperties -> SettingOrigin.CONFIG_FILE
            else -> SettingOrigin.APPLICATION
        }
    }

    private fun shadowedBy(key: String, winnerOrdinal: Int, winner: SettingOrigin): List<SettingOrigin> {
        val lower = config.configSources
            .filter { it.ordinal in 1 until winnerOrdinal && it.hasValue(key) }
            .map { source ->
                when {
                    source is SysPropConfigSource -> SettingOrigin.SYSTEM_PROPERTY
                    source is EnvConfigSource -> SettingOrigin.ENVIRONMENT
                    // The merged source also mirrors env and system values: only its file part counts here.
                    source.isLemlineSource() ->
                        if (key in fileProperties) SettingOrigin.CONFIG_FILE else null
                    else -> SettingOrigin.APPLICATION
                }
            }
        val fromFile = if (winner < SettingOrigin.CONFIG_FILE && key in fileProperties) listOf(SettingOrigin.CONFIG_FILE) else emptyList()
        return (lower.filterNotNull() + fromFile).filter { it > winner }.distinct().sorted()
    }

    /** SmallRye may decorate source names, e.g. `PropertiesConfigSource[source=LemlineConfigSource]`. */
    private fun ConfigSource.isLemlineSource(): Boolean =
        this is LemlineConfigSource || name.orEmpty().contains(LemlineConfigConstants.CONFIG_SOURCE_NAME)

    private fun ConfigSource.hasValue(key: String): Boolean = runCatching { getValue(key) != null }.getOrDefault(false)

    private fun detail(key: String, origin: SettingOrigin): String = when (origin) {
        SettingOrigin.SYSTEM_PROPERTY -> key
        SettingOrigin.ENVIRONMENT -> environmentName(key)
        SettingOrigin.CONFIG_FILE -> filePath.orEmpty()
        SettingOrigin.APPLICATION, SettingOrigin.DEFAULT, SettingOrigin.UNSET -> ""
    }

    companion object {
        /** MicroProfile environment name of a key: `lemline.gateway.cors.origins` → `LEMLINE_GATEWAY_CORS_ORIGINS`. */
        fun environmentName(key: String): String = key.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }
}

/** Lists the leaf keys of a `@ConfigMapping` interface, so a new setting cannot be forgotten. */
object ConfigMappingKeys {
    fun of(prefix: String, mapping: Class<*>): List<DeclaredKey> = walk(prefix, mapping, 0)

    /** Keys the gateway reports: its own settings plus the namespace pin that restricts what it serves. */
    val gateway: List<DeclaredKey> by lazy {
        of("lemline.gateway", LemlineConfiguration.GatewayConfig::class.java) + DeclaredKey("lemline.namespaces", null)
    }

    private fun walk(prefix: String, type: Class<*>, depth: Int): List<DeclaredKey> {
        if (depth > 8) return emptyList()
        return type.methods
            .filter { it.parameterCount == 0 && !it.isDefault && it.declaringClass == type }
            .sortedBy { it.name }
            .flatMap { method ->
                val key = "$prefix.${nameOf(method)}"
                val nested = nestedMapping(method)
                if (nested != null) walk(key, nested, depth + 1)
                else listOf(DeclaredKey(key, method.getAnnotation(WithDefault::class.java)?.value))
            }
    }

    private fun nameOf(method: Method): String =
        method.getAnnotation(WithName::class.java)?.value
            ?: method.name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    /** The interface a method maps to, directly or through `Optional`, or null for a leaf value. */
    private fun nestedMapping(method: Method): Class<*>? {
        val raw = method.returnType
        val target = if (raw == Optional::class.java) {
            ((method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull() as? Class<*>) ?: return null
        } else {
            raw
        }
        return target.takeIf { it.isInterface && it.enclosingClass == LemlineConfiguration::class.java }
    }
}
