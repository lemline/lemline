// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.outbox.bases.WaitOutboxTest
import com.lemline.worker.tests.profiles.MySQLProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * MySQL-specific implementation of WaitOutboxTest.
 */
@QuarkusTest
@TestProfile(MySQLProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MySQLWaitOutboxTest : WaitOutboxTest()
