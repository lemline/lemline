// SPDX-License-Identifier: BUSL-1.1

package com.lemline.runner.testcases.rabbitmq

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

/**
 * End-to-end workflow execution tests using RabbitMQ messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * a real RabbitMQ broker with loopback configuration (same queue for in/out channels).
 */

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQCallHttpExecutionTest : BrokerWorkflowTest(CallHttpTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQDoExecutionTest : BrokerWorkflowTest(DoTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQExportContextExecutionTest : BrokerWorkflowTest(ExportContextTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQForExecutionTest : BrokerWorkflowTest(ForTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQForkExecutionTest : BrokerWorkflowTest(ForkTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQIfExecutionTest : BrokerWorkflowTest(IfConditionTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQRunScriptExecutionTest : BrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQRunShellExecutionTest : BrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQRunWorkflowExecutionTest : BrokerWorkflowTest(RunWorkflowTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQSetExecutionTest : BrokerWorkflowTest(SetTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQSwitchExecutionTest : BrokerWorkflowTest(SwitchTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQTryExecutionTest : BrokerWorkflowTest(TryTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQWaitExecutionTest : BrokerWorkflowTest(WaitTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
internal class RabbitMQListenExecutionTest : BrokerWorkflowTest(ListenTestCases.cases)
