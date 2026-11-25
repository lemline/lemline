// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLParser
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.toKotlinTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/**
 * Parser for mock configuration files.
 *
 * Supports both YAML and JSON formats. The format is automatically detected
 * based on file extension (.yaml, .yml for YAML; .json for JSON).
 *
 * YAML is read as a data tree before decoding with the same serializers as JSON.
 */
object MockConfigurationParser {

    private val yaml = LemlineJson.yamlMapper.reader()
        .with(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

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
        return json.decodeFromJsonElement(yaml.readTree(content).toKotlinTree())
    }

    /**
     * Parse JSON string into MockConfiguration.
     */
    fun fromJson(content: String): MockConfiguration {
        if (content.isBlank()) return MockConfiguration.empty()
        return json.decodeFromString(content)
    }
}
