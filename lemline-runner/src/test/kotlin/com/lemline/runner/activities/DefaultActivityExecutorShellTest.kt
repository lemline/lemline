// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.json.*
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.random.random
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessageCodec
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.LINUX, OS.MAC)
class DefaultActivityExecutorShellTest {
    @Test
    fun `runner executes ordered shell arguments and stdin after persistence round trip`() = runTest {
        val original = WorkflowEvent.RunShellStarted(
            nodeStack = NodeStack.random(),
            input = jsonValue("input"),
            config = RunShellConfig(
                command = "sh",
                arguments = listOf("-c", "printf '<%s>\\n' \"\$@\"; cat", "shell", "repeat", "", "repeat", "two words"),
                stdin = "standard input",
            ),
        )
        val persisted = InstanceMessageCodec.workflowStateToDbJson(original)
        val restored = InstanceMessageCodec.workflowStateFromDbJson(persisted) as WorkflowEvent.RunShellStarted
        val output = DefaultActivityExecutor().execute(restored)
        assertEquals(jsonValue("<repeat>\n<>\n<repeat>\n<two words>\nstandard input"), output)
    }
}
