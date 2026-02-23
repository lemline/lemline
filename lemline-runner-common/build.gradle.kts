import java.util.ArrayDeque
import java.util.Locale
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Apply the shared build logic from a convention plugin.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)

    id("java-test-fixtures")
}

dependencies {
    // Internal modules
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-messages-proto"))

    // KotlinX ecosystem
    implementation(libs.bundles.kotlinxEcosystem)

    // Quarkus core (for CDI annotations, scheduler)
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-scheduler")

    // SmallRye Reactive Messaging API (for Message type) - provides extended Message interface
    implementation(libs.smallrye.reactive.messaging.api)

    // Quarkus Reactive Messaging extensions (for ackSuspending/nackSuspending and metadata types)
    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-messaging-rabbitmq")

    // Micrometer (for MessageSubscriberMetrics)
    implementation("io.quarkus:quarkus-micrometer")

    // Serverless Workflow SDK (for InstanceMessage state types)
    implementation(libs.serverlessworkflow.api)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation(libs.mockk)

    // Test Fixtures - Database utilities for manual DI testing
    testFixturesImplementation(libs.bundles.kotlinxEcosystem)
    testFixturesImplementation(project(":lemline-common"))
    testFixturesImplementation(project(":lemline-core"))
    testFixturesImplementation(project(":lemline-runner-common"))

    // H2 Database for in-memory testing
    testFixturesImplementation("com.h2database:h2:2.3.232")

    // HikariCP for connection pooling
    testFixturesImplementation("com.zaxxer:HikariCP:6.0.0")

    // Flyway for migrations
    testFixturesImplementation("org.flywaydb:flyway-core:11.4.0")
    testFixturesImplementation("org.flywaydb:flyway-database-postgresql:11.4.0")
    testFixturesImplementation("org.flywaydb:flyway-mysql:11.4.0")

    // Test Fixtures - Testing framework dependencies for base test classes
    testFixturesImplementation(enforcedPlatform(libs.kotest.bom))
    testFixturesImplementation("io.kotest:kotest-assertions-core")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testFixturesImplementation(libs.mockk)

    // Testcontainers for PostgreSQL and MySQL testing
    testFixturesImplementation(enforcedPlatform(libs.testcontainers.bom))
    testFixturesImplementation("org.testcontainers:testcontainers")
    testFixturesImplementation("org.testcontainers:postgresql")
    testFixturesImplementation("org.testcontainers:mysql")

    // JDBC drivers for PostgreSQL and MySQL
    testFixturesImplementation("org.postgresql:postgresql:42.7.2")
    testFixturesImplementation("com.mysql:mysql-connector-j:8.4.0")
}

abstract class GenerateLemlineConfigKeysTask : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = sourceFile.get().asFile.readLines()

        val configMappingRegex = Regex("""@ConfigMapping\s*\(\s*prefix\s*=\s*"([^"]+)"""")
        val withNameRegex = Regex("""@WithName\("([^"]+)"\)""")
        val interfaceRegex = Regex("""\binterface\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        val functionRegex = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)\s*:\s*([^\s{]+)""")

        data class MethodDef(val name: String, val withName: String?, val returnType: String)
        data class InterfaceDef(
            val qname: String,
            val bodyDepth: Int,
            val methods: MutableList<MethodDef> = mutableListOf()
        )

        var prefix = "lemline"
        var depth = 0
        var pendingWithName: String? = null
        val interfaces = linkedMapOf<String, InterfaceDef>()
        val stack = ArrayDeque<InterfaceDef>()

        source.forEach { rawLine ->
            val line = rawLine.substringBefore("//").trim()
            if (line.isBlank()) {
                return@forEach
            }

            configMappingRegex.find(line)?.let { match ->
                prefix = match.groupValues[1]
            }
            withNameRegex.find(line)?.let { match ->
                pendingWithName = match.groupValues[1]
            }

            val interfaceMatch = interfaceRegex.find(line)
            if (interfaceMatch != null) {
                val name = interfaceMatch.groupValues[1]
                val parent = stack.lastOrNull()?.qname
                val qname = if (parent == null) name else "$parent.$name"
                val bodyDepth = depth + 1
                val iface = InterfaceDef(qname, bodyDepth)
                interfaces[qname] = iface
                stack.addLast(iface)
                pendingWithName = null
            } else {
                val current = stack.lastOrNull()
                if (current != null && depth == current.bodyDepth) {
                    functionRegex.find(line)?.let { match ->
                        current.methods += MethodDef(
                            name = match.groupValues[1],
                            withName = pendingWithName,
                            returnType = match.groupValues[2].removeSuffix(",")
                        )
                        pendingWithName = null
                    }
                }
            }

            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            depth += opens - closes
            while (stack.isNotEmpty() && depth < stack.last().bodyDepth) {
                stack.removeLast()
            }
        }

        val root = interfaces[prefixToRootInterfaceName(prefix)]
            ?: interfaces["LemlineConfiguration"]
            ?: error("Could not locate root Lemline configuration interface in ${sourceFile.get().asFile}.")

        fun normalizeType(raw: String): String {
            val type = raw.removeSuffix("?")
            return when {
                type.startsWith("Optional<") && type.endsWith(">") -> type.removePrefix("Optional<").removeSuffix(">")
                type.startsWith("java.util.Optional<") && type.endsWith(">") ->
                    type.removePrefix("java.util.Optional<").removeSuffix(">")
                else -> type
            }
        }

        fun resolveInterface(currentQname: String, typeName: String): String? {
            var scope = currentQname
            while (true) {
                val candidate = "$scope.$typeName"
                if (interfaces.containsKey(candidate)) return candidate
                val lastDot = scope.lastIndexOf('.')
                if (lastDot < 0) break
                scope = scope.substring(0, lastDot)
            }
            return interfaces[typeName]?.qname
        }

        fun camelToKebab(value: String): String = value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase(Locale.ROOT)

        val keys = linkedSetOf<String>()
        val visited = mutableSetOf<Pair<String, String>>()

        fun visit(interfaceQname: String, pathPrefix: String) {
            val marker = interfaceQname to pathPrefix
            if (!visited.add(marker)) return

            val iface = interfaces[interfaceQname]
                ?: error("Missing interface metadata for '$interfaceQname'.")

            iface.methods.forEach { method ->
                val segment = method.withName ?: camelToKebab(method.name)
                val key = "$pathPrefix.$segment"
                keys += key

                val nestedType = normalizeType(method.returnType)
                resolveInterface(interfaceQname, nestedType)?.let { nested ->
                    visit(nested, key)
                }
            }
        }

        visit(root.qname, prefix)

        val segmentOverrides = mapOf(
            "postgresql" to listOf("postgres"),
            "lifecycleevents" to listOf("lifecycle", "events")
        )

        fun segmentToTokens(segment: String): List<String> = segment
            .replace('-', '_')
            .split('_')
            .filter { it.isNotBlank() }
            .flatMap { part ->
                part.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                    .split('_')
                    .filter { it.isNotBlank() }
                    .flatMap { token ->
                        segmentOverrides[token.lowercase(Locale.ROOT)] ?: listOf(token.lowercase(Locale.ROOT))
                    }
            }

        fun keyToConstantName(key: String): String {
            val keyWithoutPrefix = key.removePrefix("$prefix.")
            val name = keyWithoutPrefix
                .split('.')
                .flatMap(::segmentToTokens)
                .joinToString("_") { it.uppercase(Locale.ROOT) }
            val prefixed = "LEMLINE_$name"
            return if (prefixed.firstOrNull()?.isDigit() == true) "_$prefixed" else prefixed
        }

        val constantsByName = linkedMapOf<String, String>()
        keys.toSortedSet().forEach { key ->
            val name = keyToConstantName(key)
            val previous = constantsByName[name]
            if (previous != null && previous != key) {
                error(
                    "Generated constant name collision for '$name': '$previous' vs '$key'. " +
                        "Adjust naming rules in GenerateLemlineConfigKeysTask."
                )
            }
            constantsByName[name] = key
        }

        val outputFile = outputDir.file("com/lemline/runner/common/config/LemlineConfigKeys.kt").get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            buildString {
                appendLine("// SPDX-License-Identifier: BUSL-1.1")
                appendLine("// Generated from lemline-runner-config/LemlineConfiguration.kt. Do not edit manually.")
                appendLine("package com.lemline.runner.common.config")
                appendLine()
                appendLine("const val LEMLINE_PREFIX = \"$prefix.\"")
                appendLine()
                constantsByName.toSortedMap().forEach { (name, key) ->
                    appendLine("const val $name = \"$key\"")
                }
            }
        )
    }

    private fun prefixToRootInterfaceName(prefix: String): String = when (prefix) {
        "lemline" -> "LemlineConfiguration"
        else -> "${prefix.replaceFirstChar { it.uppercaseChar() }}Configuration"
    }
}

val generatedLemlineConfigKeysDir = layout.buildDirectory.dir("generated/sources/lemline-config-keys/kotlin/main")

val generateLemlineConfigKeys by tasks.registering(GenerateLemlineConfigKeysTask::class) {
    sourceFile.set(
        rootProject.layout.projectDirectory.file(
            "lemline-runner-config/src/main/kotlin/com/lemline/runner/config/shared/LemlineConfiguration.kt"
        )
    )
    outputDir.set(generatedLemlineConfigKeysDir)
}

sourceSets.named("main") {
    // Task-backed source dir ensures generated keys are recreated after clean before compilation/testing.
    java.srcDir(generateLemlineConfigKeys)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateLemlineConfigKeys)
}

tasks.named("classes") {
    dependsOn(generateLemlineConfigKeys)
}

tasks.named("testClasses") {
    dependsOn(generateLemlineConfigKeys)
}
