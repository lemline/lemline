// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.rabbitmq

import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.ExportContextTestCases
import com.lemline.core.testcases.ForTaskTestCases
import com.lemline.core.testcases.ForkTaskTestCases
import com.lemline.core.testcases.IfConditionTestCases
import com.lemline.core.testcases.RunScriptTestCases
import com.lemline.core.testcases.RunShellTestCases
import com.lemline.core.testcases.RunWorkflowTestCases
import com.lemline.core.testcases.SetTaskTestCases
import com.lemline.core.testcases.SwitchTaskTestCases
import com.lemline.core.testcases.TryTaskTestCases
import com.lemline.core.testcases.WaitTestCases
import com.lemline.runner.testcases.AbstractBrokerWorkflowTest
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * End-to-end workflow execution tests using RabbitMQ messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * a real RabbitMQ broker with loopback configuration (same queue for in/out channels).
 */

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQCallHttpExecutionTest : AbstractBrokerWorkflowTest(CallHttpTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQDoExecutionTest : AbstractBrokerWorkflowTest(DoTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQExportContextExecutionTest : AbstractBrokerWorkflowTest(ExportContextTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQForExecutionTest : AbstractBrokerWorkflowTest(ForTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQForkExecutionTest : AbstractBrokerWorkflowTest(ForkTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQIfExecutionTest : AbstractBrokerWorkflowTest(IfConditionTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQRunScriptExecutionTest : AbstractBrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQRunShellExecutionTest : AbstractBrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQRunWorkflowExecutionTest : AbstractBrokerWorkflowTest(RunWorkflowTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQSetExecutionTest : AbstractBrokerWorkflowTest(SetTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQSwitchExecutionTest : AbstractBrokerWorkflowTest(SwitchTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQTryExecutionTest : AbstractBrokerWorkflowTest(TryTaskTestCases.cases)

@QuarkusTest
@TestProfile(RabbitMQTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RabbitMQWaitExecutionTest : AbstractBrokerWorkflowTest(WaitTestCases.cases)
