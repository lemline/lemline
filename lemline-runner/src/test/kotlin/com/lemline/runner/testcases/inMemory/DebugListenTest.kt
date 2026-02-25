// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.runner.testcases.bases.InMemoryWorkflowTest
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DebugListenTest : InMemoryWorkflowTest(emptyList())
