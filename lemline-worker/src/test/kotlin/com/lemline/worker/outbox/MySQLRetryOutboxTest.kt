package com.lemline.worker.outbox

import com.lemline.worker.outbox.bases.RetryOutboxTest
import com.lemline.worker.tests.resources.MySQLTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * MySQL-specific implementation of RetryOutboxTest.
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("mysql")
class MySQLRetryOutboxTest : RetryOutboxTest()
