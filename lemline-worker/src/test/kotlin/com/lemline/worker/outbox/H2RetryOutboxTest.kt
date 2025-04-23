// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.outbox.bases.RetryOutboxTest
import com.lemline.worker.tests.profiles.H2KafkaProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.TestInstance

/**
 * MySQL-specific implementation of RetryOutboxTest.
 */
@QuarkusTest
@TestProfile(H2KafkaProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class H2RetryOutboxTest : RetryOutboxTest()
