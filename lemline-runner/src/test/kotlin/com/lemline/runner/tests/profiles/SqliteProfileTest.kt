// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.config.LemlineConfiguration
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

@RequiresDocker
@TestProfile(SqliteProfile::class)
@QuarkusTest
class SqliteProfileTest {
    @Inject
    lateinit var config: LemlineConfiguration

    @Test
    fun `starts SQLite messaging with PostgreSQL orchestration`() {
        assertEquals(DatabaseType.POSTGRESQL.configValue, config.database().type())
        assertEquals(MessagingType.SQLITE.configValue, config.messaging().type())
        assertTrue(Files.isRegularFile(Path.of(config.messaging().sqlite().path().orElseThrow())))
    }
}
