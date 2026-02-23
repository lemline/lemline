// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.LemlineConfiguration
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestProfile(InMemoryProfile::class)
@QuarkusTest
class InMemoryProfileTest {

    @Inject
    lateinit var lemlineConfig: LemlineConfiguration

    @Test
    fun `check the InMemoryProfile profile`() {
        // Check the default values of the configuration
        assertEquals(DatabaseType.H2.configValue, lemlineConfig.database().type())
        assertEquals(MessagingType.IN_MEMORY.configValue, lemlineConfig.messaging().type())
    }
}
