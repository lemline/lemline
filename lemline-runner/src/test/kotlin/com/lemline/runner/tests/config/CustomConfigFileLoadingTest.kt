// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.tests.profiles.CustomConfigFileProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestProfile(CustomConfigFileProfile::class)
@QuarkusTest
class CustomConfigFileLoadingTest {

    @Inject
    lateinit var config: LemlineConfiguration

    @Test
    fun `config file database type is loaded`() {
        assertEquals(DB_TYPE_IN_MEMORY, config.database().type())
    }

    @Test
    fun `config file database migrate-at-start is loaded`() {
        assertTrue(config.database().migrateAtStart())
    }

    @Test
    fun `config file database baseline-on-migrate is loaded`() {
        assertTrue(config.database().baselineOnMigrate())
    }

    @Test
    fun `config file messaging type is loaded`() {
        assertEquals(MSG_TYPE_IN_MEMORY, config.messaging().type())
    }

    @Test
    fun `config file outbox processing values are loaded`() {
        val waitConfig = config.outbox().wait().orElseThrow()
        val outbox = waitConfig.outbox()

        assertEquals("7s", outbox.every())
        assertEquals(777, outbox.batchSize())
        assertEquals("2s", outbox.initialJitter())
        assertEquals("20s", outbox.retryDelay())
        assertEquals(7, outbox.maxAttempts())
    }

    @Test
    fun `config file outbox cleanup values are loaded`() {
        val waitConfig = config.outbox().wait().orElseThrow()
        val cleanup = waitConfig.cleanup()

        assertEquals("3h", cleanup.every())
        assertEquals("5d", cleanup.after())
        assertEquals(333, cleanup.batchSize())
    }

    @Test
    fun `config file metrics values are loaded`() {
        assertEquals(9999, config.metrics().port())
        assertEquals("/test/metrics", config.metrics().path())
    }

    @Test
    fun `config file orchestrator mode is loaded`() {
        assertEquals(LemlineConfiguration.OrchestratorMode.ALL, config.orchestrator().mode())
    }
}
