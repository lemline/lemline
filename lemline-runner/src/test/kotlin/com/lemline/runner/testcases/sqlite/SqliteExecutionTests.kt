// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.sqlite

import com.lemline.core.testcases.CallFunctionTestCases
import com.lemline.core.testcases.CallHttpTestCases
import com.lemline.core.testcases.DoTaskTestCases
import com.lemline.core.testcases.ExtensionTaskTestCases
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

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteCallFunctionExecutionTest : BrokerWorkflowTest(CallFunctionTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteCallHttpExecutionTest : BrokerWorkflowTest(CallHttpTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteDoExecutionTest : BrokerWorkflowTest(DoTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteExportContextExecutionTest : BrokerWorkflowTest(ExportContextTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteForExecutionTest : BrokerWorkflowTest(ForTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteForkExecutionTest : BrokerWorkflowTest(ForkTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteIfExecutionTest : BrokerWorkflowTest(IfConditionTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteRunScriptExecutionTest : BrokerWorkflowTest(
    RunScriptTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteRunShellExecutionTest : BrokerWorkflowTest(
    RunShellTestCases.cases,
    excludeTags = setOf("windows-only")
)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteRunWorkflowExecutionTest : BrokerWorkflowTest(RunWorkflowTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteSetExecutionTest : BrokerWorkflowTest(SetTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteSwitchExecutionTest : BrokerWorkflowTest(SwitchTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteTryExecutionTest : BrokerWorkflowTest(TryTaskTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteWaitExecutionTest : BrokerWorkflowTest(WaitTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteListenExecutionTest : BrokerWorkflowTest(ListenTestCases.cases)

@RequiresDocker
@QuarkusTest
@TestProfile(SqliteTestCaseProfile::class)
internal class SqliteExtensionExecutionTest : BrokerWorkflowTest(ExtensionTaskTestCases.cases)
