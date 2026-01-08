// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PATH_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.METRICS_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.tests.profiles.MinimalConfigFileProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@TestProfile(MinimalConfigFileProfile::class)
@QuarkusTest
class MinimalConfigFileLoadingTest {

    @Inject
    lateinit var config: LemlineConfiguration

    @Test
    fun `minimal config file database type is loaded`() {
        assertEquals(DB_TYPE_IN_MEMORY, config.database().type())
    }

    @Test
    fun `minimal config file uses default migrate-at-start`() {
        assertFalse(config.database().migrateAtStart())
    }

    @Test
    fun `minimal config file uses default baseline-on-migrate`() {
        assertFalse(config.database().baselineOnMigrate())
    }

    @Test
    fun `minimal config file messaging type is loaded`() {
        assertEquals(MSG_TYPE_IN_MEMORY, config.messaging().type())
    }

    @Test
    fun `minimal config file uses default metrics port`() {
        assertEquals(METRICS_PORT_DEFAULT.toInt(), config.metrics().port())
    }

    @Test
    fun `minimal config file uses default metrics path`() {
        assertEquals(METRICS_PATH_DEFAULT, config.metrics().path())
    }

    @Test
    fun `minimal config file uses default orchestrator mode`() {
        assertEquals(LemlineConfiguration.OrchestratorMode.ACTION, config.orchestrator().mode())
    }

    @Test
    fun `minimal config file uses default outbox processing values`() {
        val waitConfig = config.outbox().wait().orElseThrow()
        val outbox = waitConfig.outbox()

        assertEquals("10s", outbox.every())
        assertEquals(1000, outbox.batchSize())
        assertEquals("3s", outbox.initialJitter())
        assertEquals("30s", outbox.retryDelay())
        assertEquals(5, outbox.maxAttempts())
    }

    @Test
    fun `minimal config file uses default outbox cleanup values`() {
        val waitConfig = config.outbox().wait().orElseThrow()
        val cleanup = waitConfig.cleanup()

        assertEquals("1h", cleanup.every())
        assertEquals("7d", cleanup.after())
        assertEquals(1000, cleanup.batchSize())
    }
}
