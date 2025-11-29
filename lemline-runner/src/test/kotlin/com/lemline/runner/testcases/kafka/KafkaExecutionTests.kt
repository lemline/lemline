// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.kafka

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
 * End-to-end workflow execution tests using Kafka messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * a real Kafka broker with loopback configuration (same topic for in/out channels).
 */

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaCallHttpExecutionTest : AbstractBrokerWorkflowTest(CallHttpTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaDoExecutionTest : AbstractBrokerWorkflowTest(DoTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaExportContextExecutionTest : AbstractBrokerWorkflowTest(ExportContextTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaForExecutionTest : AbstractBrokerWorkflowTest(ForTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaForkExecutionTest : AbstractBrokerWorkflowTest(ForkTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaIfExecutionTest : AbstractBrokerWorkflowTest(IfConditionTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaRunScriptExecutionTest : AbstractBrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaRunShellExecutionTest : AbstractBrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaRunWorkflowExecutionTest : AbstractBrokerWorkflowTest(RunWorkflowTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaSetExecutionTest : AbstractBrokerWorkflowTest(SetTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaSwitchExecutionTest : AbstractBrokerWorkflowTest(SwitchTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaTryExecutionTest : AbstractBrokerWorkflowTest(TryTaskTestCases.cases)

@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class KafkaWaitExecutionTest : AbstractBrokerWorkflowTest(WaitTestCases.cases)
