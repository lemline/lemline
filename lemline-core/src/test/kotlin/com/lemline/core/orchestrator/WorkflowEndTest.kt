// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.json.parseJson
import com.lemline.common.values.NodePosition
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.getWorkflowToTest
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowEvent
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WorkflowEndTest {
    @Test
    fun `end applies enclosing task and workflow output and exports while unwinding`() = runTest {
        val outcome = assertIs<WorkflowEvent.WorkflowCompleted>(run($$"""
            use:
              extensions:
                - observe:
                    extend: composite
                    when: '${ $task.name == "enclosing" }'
                    after:
                      - overwrite:
                          set: { exported: skipped }
                          export: { as: '${ . }' }
            do:
              - enclosing:
                  input:
                    from: '${ { prefix: "status" } }'
                  do:
                    - setStatus:
                        set: { status: done }
                        then: end
                    - innerSibling:
                        set: { status: skipped }
                  output:
                    as: '${ $input.prefix + ": " + .status }'
                  export:
                    as: '${ { exported: . } }'
              - outerSibling:
                  set: { status: skipped }
            output:
              as: '${ { result: ., exported: $context.exported } }'
        """))

        assertEquals(parseJson("""{"result":"status: done","exported":"status: done"}"""), outcome.output)
    }

    @Test
    fun `end validates the transformed workflow output`() = runTest {
        val outcome = assertIs<WorkflowEvent.WorkflowFaulted>(run($$"""
            do:
              - setStatus:
                  set: { status: done }
                  then: end
            output:
              as: '${ .status }'
              schema:
                format: json
                document:
                  type: number
        """))

        assertTrue(outcome.error.type.endsWith(WorkflowErrorType.VALIDATION.type), outcome.error.type)
        assertEquals(NodePosition.root.toString(), outcome.error.position)
    }

    private suspend fun run(yaml: String): WorkflowEvent.Outcome = FullOrchestrator.resume(
        workflow = getWorkflowToTest(yaml),
        command = StepByStepOrchestrator.initCmd(),
        serde = true,
        activityExecutor = MockActivityExecutor.empty {},
        functionResolver = MockFunctionResolver(),
        cloudEventHook = InMemoryCloudEventHook(),
        lifecycleHook = LifecycleEventHook.NOOP,
    )
}
