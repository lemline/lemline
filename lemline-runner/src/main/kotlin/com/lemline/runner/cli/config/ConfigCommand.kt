// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.lemline.runner.LemlineApplication
import com.lemline.runner.cli.LemlineMixin
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import org.eclipse.microprofile.config.Config
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

@Unremovable
@Command(
    name = "config",
    description = ["Display current configuration"],
)
class ConfigCommand : Runnable {
    @Mixin
    lateinit var mixin: LemlineMixin

    enum class Format {
        PROPERTIES,
        YAML,
    }

    @Option(
        names = ["-f", "--format"],
        description = ["Output format (properties, yaml)"],
        converter = [FormatOptionConverter::class]
    )
    var format: Format = Format.YAML

    @Option(
        names = ["-a", "--all"],
        description = ["Show all properties, not just lemline.*"]
    )
    var all: Boolean = false

    @Inject
    lateinit var lemlineConfig: Config

    override fun run() {

        val properties = lemlineConfig.propertyNames.asSequence()
            .filter { all || it.startsWith("lemline.") }
            .filter { it.isNotBlank() } // Skip empty property names
            .sorted()
            .associateWith {
                lemlineConfig.getOptionalValue(it, String::class.java).orElse("")
            }

        println(
            "# Configuration from " +
                (LemlineApplication.Companion.configPath?.toAbsolutePath()?.let { "$it + " } ?: "") +
                "default values."
        )
        when (format) {
            Format.PROPERTIES -> {
                properties.forEach { (key, value) ->
                    // We need to escape newlines to keep output readable
                    val escapedValue = value
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                    println("$key=$escapedValue")
                }
            }

            Format.YAML -> {
                val nestedProperties = createNestedStructure(properties)
                val mapper = ObjectMapper(
                    YAMLFactory()
                        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                )
                print(mapper.writeValueAsString(nestedProperties))
            }
        }
    }

    private fun createNestedStructure(properties: Map<String, String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        properties.forEach { (key, value) ->
            // Split the key into parts, but preserve quoted strings
            val parts = mutableListOf<String>()
            var current = StringBuilder()
            var inQuotes = false

            // This ensures that dots inside quoted strings are not treated as delimiters,
            // preserving the structure of keys with quoted segments.
            for (char in key) {
                when {
                    char == '"' -> {
                        inQuotes = !inQuotes
                        current.append(char)
                    }

                    char == '.' && !inQuotes -> {
                        if (current.isNotEmpty()) {
                            parts.add(current.toString())
                            current = StringBuilder()
                        }
                    }

                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) {
                parts.add(current.toString())
            }

            // Skip if no parts were found
            if (parts.isEmpty()) {
                return@forEach
            }

            var currentMap = result
            for (i in 0 until parts.size - 1) {
                val part = parts[i]
                // Keep quotes for keys containing dots
                val next = currentMap.getOrPut(part) { mutableMapOf<String, Any>() }
                if (next is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    currentMap = next as MutableMap<String, Any>
                } else {
                    val newMap = mutableMapOf<String, Any>()
                    currentMap[part] = newMap
                    currentMap = newMap
                }
            }

            val lastPart = parts.last()
            currentMap[lastPart] = value
        }

        return result
    }

    private class FormatOptionConverter : ITypeConverter<Format> {
        override fun convert(value: String): Format = try {
            Format.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Allowed values are: ${Format.entries.joinToString(", ").lowercase()}."
            )
        }
    }
}
