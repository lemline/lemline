// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.LemlineConfigConstants.DB_TYPE_IN_MEMORY
import com.lemline.runner.config.LemlineConfigConstants.MSG_TYPE_IN_MEMORY
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
class LemlineConfigurationTest {

    @Inject
    lateinit var lemlineConfig: LemlineConfiguration

    @Test
    fun testConfigurationValues() {
        // Check the default values of the configuration
        assertEquals(DB_TYPE_IN_MEMORY, lemlineConfig.database().type())
        assertEquals(MSG_TYPE_IN_MEMORY, lemlineConfig.messaging().type())

        assertEquals(5, lemlineConfig.retry().outbox().maxAttempts())
        assertEquals(1000, lemlineConfig.retry().outbox().batchSize())
        assertEquals(Duration.ofSeconds(30), lemlineConfig.retry().outbox().initialDelay().toDuration())
        assertEquals(Duration.ofHours(1), lemlineConfig.wait().cleanup().every().toDuration())
    }
}
