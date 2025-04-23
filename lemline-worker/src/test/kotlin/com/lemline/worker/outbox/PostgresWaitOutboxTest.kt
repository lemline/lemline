// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.outbox.bases.WaitOutboxTest
import com.lemline.worker.tests.resources.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Postgres-specific implementation of WaitOutboxTest.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("postgresql")
internal class PostgresWaitOutboxTest : WaitOutboxTest()
