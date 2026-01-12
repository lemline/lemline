// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)

package com.lemline.runner.testcases.pgmq

import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.ExportContextTestCases
import com.lemline.core.testcases.ForTaskTestCases
import com.lemline.core.testcases.ForkTaskTestCases
import com.lemline.core.testcases.IfConditionTestCases
import com.lemline.core.testcases.ListenTestCases
import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.core.testcases.RunShellTestCases
import com.lemline.core.testcases.RunWorkflowTestCases
import com.lemline.core.testcases.SetTaskTestCases
import com.lemline.core.testcases.SwitchTaskTestCases
import com.lemline.core.testcases.TryTaskTestCases
import com.lemline.core.testcases.WaitTestCases
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.testcases.bases.BrokerWorkflowTest
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * End-to-end workflow execution tests using PGMQ (PostgreSQL Message Queue) messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * a real PostgreSQL-based PGMQ queue with loopback configuration (same queue for in/out channels).
 */

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqCallHttpExecutionTest : BrokerWorkflowTest(CallHttpTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqDoExecutionTest : BrokerWorkflowTest(DoTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqExportContextExecutionTest : BrokerWorkflowTest(ExportContextTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqForExecutionTest : BrokerWorkflowTest(ForTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqForkExecutionTest : BrokerWorkflowTest(ForkTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqIfExecutionTest : BrokerWorkflowTest(IfConditionTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqRunScriptExecutionTest : BrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqRunShellExecutionTest : BrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqRunWorkflowExecutionTest : BrokerWorkflowTest(RunWorkflowTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqSetExecutionTest : BrokerWorkflowTest(SetTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqSwitchExecutionTest : BrokerWorkflowTest(SwitchTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqTryExecutionTest : BrokerWorkflowTest(TryTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqWaitExecutionTest : BrokerWorkflowTest(WaitTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(PgmqTestCaseProfile::class)
internal class PgmqListenExecutionTest : BrokerWorkflowTest(ListenTestCases.cases)
