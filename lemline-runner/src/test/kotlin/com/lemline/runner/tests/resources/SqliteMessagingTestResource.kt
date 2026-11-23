// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.config.LEMLINE_MESSAGING_SQLITE_PATH
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class SqliteMessagingTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var directory: Path

    override fun start(): Map<String, String> {
        directory = Files.createTempDirectory("lemline-runner-sqlite-")
        return mapOf(LEMLINE_MESSAGING_SQLITE_PATH to directory.resolve("messaging.db").toString())
    }

    @OptIn(ExperimentalPathApi::class)
    override fun stop() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }
}
