// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Parser for mock configuration files.
 *
 * Supports both YAML and JSON formats. The format is automatically detected
 * based on file extension (.yaml, .yml for YAML; .json for JSON).
 *
 * Uses kotlinx.serialization with kaml for YAML support.
 */
object MockConfigurationParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Load mock configuration from a file.
     *
     * @param path Path to configuration file
     * @return Parsed MockConfiguration
     * @throws IllegalArgumentException if file doesn't exist or has unsupported extension
     */
    fun fromFile(path: String): MockConfiguration {
        val file = File(path)
        require(file.exists()) { "Mock configuration file not found: $path" }

        val content = file.readText()
        return when {
            path.endsWith(".yaml") || path.endsWith(".yml") -> fromYaml(content)
            path.endsWith(".json") -> fromJson(content)
            else -> {
                // Try YAML first (it's a superset of JSON)
                try {
                    fromYaml(content)
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Could not parse mock config '$path'. Use .yaml, .yml, or .json extension.",
                        e
                    )
                }
            }
        }
    }

    /**
     * Parse YAML string into MockConfiguration.
     */
    fun fromYaml(content: String): MockConfiguration {
        if (content.isBlank()) return MockConfiguration.empty()
        return yaml.decodeFromString(MockConfiguration.serializer(), content)
    }

    /**
     * Parse JSON string into MockConfiguration.
     */
    fun fromJson(content: String): MockConfiguration {
        if (content.isBlank()) return MockConfiguration.empty()
        return json.decodeFromString(MockConfiguration.serializer(), content)
    }
}
