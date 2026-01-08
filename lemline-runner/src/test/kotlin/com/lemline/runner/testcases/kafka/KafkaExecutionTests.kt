// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)

package com.lemline.runner.testcases.kafka

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
 * End-to-end workflow execution tests using Kafka messaging infrastructure.
 *
 * These tests verify that workflows execute correctly when messages flow through
 * a real Kafka broker with loopback configuration (same topic for in/out channels).
 */

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaCallHttpExecutionTest : BrokerWorkflowTest(CallHttpTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaDoExecutionTest : BrokerWorkflowTest(DoTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaExportContextExecutionTest : BrokerWorkflowTest(ExportContextTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaForExecutionTest : BrokerWorkflowTest(ForTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaForkExecutionTest : BrokerWorkflowTest(ForkTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaIfExecutionTest : BrokerWorkflowTest(IfConditionTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaRunScriptExecutionTest : BrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaRunShellExecutionTest : BrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaRunWorkflowExecutionTest : BrokerWorkflowTest(RunWorkflowTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaSetExecutionTest : BrokerWorkflowTest(SetTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaSwitchExecutionTest : BrokerWorkflowTest(SwitchTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaTryExecutionTest : BrokerWorkflowTest(TryTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaWaitExecutionTest : BrokerWorkflowTest(WaitTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaTestCaseProfile::class)
internal class KafkaListenExecutionTest : BrokerWorkflowTest(ListenTestCases.cases)
