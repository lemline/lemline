// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.packaged

import com.lemline.runner.common.test.RequiresDocker
import io.quarkus.test.junit.main.Launch
import io.quarkus.test.junit.main.LaunchResult
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest
import io.quarkus.test.junit.main.QuarkusMainLauncher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs CLI commands against the packaged artifact (`./gradlew :lemline-runner:testNative`, or
 * `quarkusIntTest` for the jar). These catch what only packaging breaks: classes Jackson needs by
 * reflection, resources left out of the native image, startup checks.
 */
@QuarkusMainIntegrationTest
internal class PackagedCliIT {

    @Test
    @Launch("--version")
    fun `prints its version`(result: LaunchResult) {
        assertTrue(result.output.contains("Lemline Runner"), result.output)
    }

    @Test
    @Launch("--help")
    fun `prints its help without a configuration file`(result: LaunchResult) {
        assertTrue(result.output.contains("definition"), result.output)
    }

    @Test
    @RequiresDocker
    fun `migrates the database and registers a workflow definition`(launcher: QuarkusMainLauncher) {
        val config = PackagedEnvironment.config.toString()

        val migrate = launcher.launch("--config", config, "migrate", "--force")
        assertEquals(0, migrate.exitCode(), migrate.output + migrate.errorOutput)

        val name = "cli-${UUID.randomUUID()}"
        val definition = PackagedEnvironment.write(
            "$name.yaml",
            """
            |document:
            |  dsl: '1.0.3'
            |  namespace: packaged
            |  name: $name
            |  version: '1.0.0'
            |do:
            |  - greet:
            |      set:
            |        message: ${'$'}{ "Hello, " + .name }
            """.trimMargin(),
        )
        val post = launcher.launch("--config", config, "definition", "post", "-f", definition.toString())
        assertEquals(0, post.exitCode(), post.output + post.errorOutput)
        assertTrue(post.output.contains("successfully created"), post.output + post.errorOutput)
    }
}
