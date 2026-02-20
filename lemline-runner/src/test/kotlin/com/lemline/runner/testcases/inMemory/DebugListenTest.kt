// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DebugListenTest : InMemoryWorkflowTest(emptyList<WorkflowTestCase>())
