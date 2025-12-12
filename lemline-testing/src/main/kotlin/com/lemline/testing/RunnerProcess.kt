// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.common.logger.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the lifecycle of a native runner binary process.
 *
 * This class spawns the lemline-runner native binary as an external process,
 * monitors its health, and handles graceful shutdown.
 *
 * @param binaryPath Path to the native runner binary
 * @param configPath Path to the runner configuration file
 * @param mockConfigPath Optional path to mock configuration file (enables test mode)
 * @param workingDir Working directory for the process
 */
class RunnerProcess(
    private val binaryPath: Path,
    private val configPath: Path,
    private val mockConfigPath: Path? = null,
    private val workingDir: Path = Path.of(".")
) : AutoCloseable {

    private val logger = logger()
    private var process: Process? = null

    /**
     * Start the runner process.
     *
     * @param startupTimeout Maximum time to wait for runner to be ready
     * @throws RunnerStartupException if runner fails to start within timeout
     */
    suspend fun start(startupTimeout: Duration = 30.seconds) {
        if (process != null) {
            throw IllegalStateException("Runner process is already running")
        }

        val command = buildCommand()
        logger.info { "Starting runner: ${command.joinToString(" ")}" }

        withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)

            // Inherit environment
            processBuilder.environment().putAll(System.getenv())

            process = processBuilder.start()

            // Wait for runner to be ready
            val ready = waitForReady(startupTimeout)
            if (!ready) {
                val output = process?.inputStream?.bufferedReader()?.readText() ?: ""
                process?.destroyForcibly()
                process = null
                throw RunnerStartupException("Runner failed to start within $startupTimeout. Output: $output")
            }

            logger.info { "Runner started successfully (PID: ${process?.pid()})" }
        }
    }

    /**
     * Stop the runner process gracefully.
     *
     * @param shutdownTimeout Maximum time to wait for graceful shutdown
     */
    suspend fun stop(shutdownTimeout: Duration = 10.seconds) {
        val proc = process ?: return
        logger.info { "Stopping runner (PID: ${proc.pid()})..." }

        withContext(Dispatchers.IO) {
            // Send SIGTERM for graceful shutdown
            proc.destroy()

            // Wait for graceful shutdown
            val exited = withTimeoutOrNull(shutdownTimeout) {
                while (proc.isAlive) {
                    delay(100.milliseconds)
                }
                true
            }

            if (exited != true) {
                logger.warn { "Runner didn't exit gracefully, forcing termination" }
                proc.destroyForcibly()
            }

            process = null
            logger.info { "Runner stopped" }
        }
    }

    override fun close() {
        process?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        process = null
    }

    /**
     * Check if the runner process is alive.
     */
    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Get the process ID (if running).
     */
    fun pid(): Long? = process?.pid()

    private fun buildCommand(): List<String> {
        val binary = binaryPath.toAbsolutePath().toString()
        val isJar = binary.endsWith(".jar")

        val cmd = if (isJar) {
            mutableListOf(
                "java", "-jar", binary,
                "listen",
                "--config=${configPath.toAbsolutePath()}"
            )
        } else {
            mutableListOf(
                binary,
                "listen",
                "--config=${configPath.toAbsolutePath()}"
            )
        }

        // Add mock config if provided (enables test mode)
        mockConfigPath?.let {
            cmd.add("--mock-config=${it.toAbsolutePath()}")
        }

        return cmd
    }

    private suspend fun waitForReady(timeout: Duration): Boolean {
        val proc = process ?: return false
        val reader = proc.inputStream.bufferedReader()

        return withTimeoutOrNull(timeout) {
            withContext(Dispatchers.IO) {
                while (proc.isAlive) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: continue
                        logger.debug { "[runner] $line" }

                        // Look for ready indicator in output
                        if (line.contains("Start consuming messages") ||
                            line.contains("Listening for messages")
                        ) {
                            return@withContext true
                        }
                    }
                    delay(50.milliseconds)
                }
                false
            }
        } ?: false
    }

    companion object {
        /**
         * Find the runner binary or JAR in standard locations.
         *
         * Searches for (in order):
         * 1. Quarkus JAR (for development)
         * 2. Native binary
         *
         * @return Path to the binary/JAR or null if not found
         */
        fun findBinary(): Path? {
            // Try multiple base paths (current dir, parent, etc.)
            val basePaths = listOf(
                File("."),                    // Current directory
                File(".."),                   // Parent (when running from module)
                File(System.getProperty("user.dir")),
                File(System.getProperty("user.dir")).parentFile
            ).filterNotNull().distinct()

            for (base in basePaths) {
                // 1. Check for Quarkus JAR (most common during development)
                val quarkusJar = File(base, "lemline-runner/build/quarkus-app/quarkus-run.jar")
                if (quarkusJar.exists()) {
                    return quarkusJar.toPath().toAbsolutePath()
                }

                // 2. Check for native binary in build directory
                val buildDir = File(base, "lemline-runner/build")
                if (buildDir.exists()) {
                    // Native binary with version suffix
                    val nativeBinary = buildDir.listFiles()?.firstOrNull {
                        it.name.startsWith("lemline-runner-") && it.name.endsWith("-runner") && it.canExecute()
                    }
                    if (nativeBinary != null) {
                        return nativeBinary.toPath().toAbsolutePath()
                    }
                }

                // 3. Check native compile output
                val nativeCompile = File(base, "lemline-runner/build/native/nativeCompile/lemline-runner")
                if (nativeCompile.exists() && nativeCompile.canExecute()) {
                    return nativeCompile.toPath().toAbsolutePath()
                }
            }

            // 4. Check installed location (global)
            val installed = File("/usr/local/bin/lemline-runner")
            if (installed.exists() && installed.canExecute()) {
                return installed.toPath()
            }

            return null
        }
    }
}

/**
 * Exception thrown when runner fails to start.
 */
class RunnerStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
