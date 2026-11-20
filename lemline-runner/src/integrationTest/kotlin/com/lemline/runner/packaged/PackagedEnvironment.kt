// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared environment for tests that run the packaged Lemline artifact (native binary or jar),
 * as built by `quarkusBuild`: one PostgreSQL container holding the workflow database, the PGMQ
 * queues and a separate lifecycle analytics database, plus a configuration file pointing at it.
 */
internal object PackagedEnvironment {
    const val ANALYTICS_DATABASE = "lemline_analytics"

    private val postgres: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("lemline")
            .withUsername("postgres")
            .withPassword("postgres")
            .also {
                it.start()
                val created = it.execInContainer("createdb", "-U", "postgres", ANALYTICS_DATABASE)
                check(created.exitCode == 0) { "Cannot create $ANALYTICS_DATABASE: ${created.stderr}" }
            }
    }

    val postgresPort: Int get() = postgres.firstMappedPort

    val workDir: Path by lazy { Files.createTempDirectory("lemline-packaged-") }

    /** Configuration file for every command, created once the database is up. */
    val config: Path by lazy {
        configuration(
            workDir.resolve("lemline.yaml"),
            "postgresql: { host: localhost, port: ${postgres.firstMappedPort}, database: $ANALYTICS_DATABASE, username: postgres, password: postgres }",
        )
    }

    fun sqliteAnalyticsConfig(directory: Path): Path = configuration(
        directory.resolve("lemline.yaml"),
        """
        |type: sqlite
        |migrate-at-start: true
        """.trimMargin(),
        """
        |  gateway:
        |    grpc: { host: 127.0.0.1 }
        |    tls: { enabled: false }
        |    authentication: { enabled: false }
        """.trimMargin(),
    )

    private fun configuration(file: Path, analytics: String, gateway: String = ""): Path {
        val port = postgres.firstMappedPort
        Files.writeString(
            file,
            """
            |lemline:
            |  database:
            |    postgresql: { host: localhost, port: $port, database: lemline, username: postgres, password: postgres }
            |  messaging:
            |    pgmq: { host: localhost, port: $port, database: lemline, username: postgres, password: postgres }
            |  analytics:
            |${analytics.prependIndent("    ")}
            |  namespaces: packaged
            |$gateway
            """.trimMargin(),
        )
        return file
    }

    fun write(name: String, content: String): Path = workDir.resolve(name).also { Files.writeString(it, content) }

    /**
     * Command line that starts the artifact Quarkus built, read from `build/quarkus-artifact.properties`:
     * the native binary itself, or `java -jar` for a jar build.
     */
    val artifactCommand: List<String> by lazy {
        val buildDir = Path.of("build").toAbsolutePath()
        val descriptor = buildDir.resolve("quarkus-artifact.properties")
        check(Files.exists(descriptor)) { "$descriptor not found: build the artifact with quarkusBuild first" }
        val properties = Properties().apply { Files.newInputStream(descriptor).use { load(it) } }
        val artifact = buildDir.resolve(properties.getProperty("path")).toString()
        when (val type = properties.getProperty("type")) {
            "native" -> listOf(artifact)
            "jar", "uber-jar" -> listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", artifact)
            else -> error("Unsupported Quarkus artifact type: $type")
        }
    }

    fun command(vararg args: String, configFile: Path = config): List<String> =
        artifactCommand + listOf("--config", configFile.toString()) + args

    /** Runs a command to completion and returns its exit code and combined output. */
    fun run(vararg args: String, timeoutSeconds: Long = 120, configFile: Path = config): Pair<Int, String> {
        val log = configFile.parent.resolve("command-${System.nanoTime()}.log").toFile()
        val process = ProcessBuilder(command(*args, configFile = configFile))
            .directory(configFile.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Command ${args.joinToString(" ")} did not finish in ${timeoutSeconds}s:\n${log.readText()}"
        }
        return process.exitValue() to log.readText()
    }
}
