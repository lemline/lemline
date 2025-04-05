package com.lemline.sw.workflows

import com.lemline.sw.utils.getWorkflowInstance
import io.serverlessworkflow.impl.WorkflowStatus
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ErrorHandlingTest {

    @Test
    fun `error in try-catch without retry continues to catch block`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/errors/not-implemented
                            status: 500
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/errors/not-implemented
                    do:
                      - setHandled:
                          set:
                            handled: @{ true }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the error was caught and handled
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `error in try-catch with retry limit reached continues to catch block`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - increment:
                        set:
                          count: @{ @context.count + 1 }
                        export:
                          as: @{ . }
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/errors/not-implemented
                            status: 500
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/errors/not-implemented
                    retry:
                      limit:
                        attempt:
                          count: 2
                      delay: PT1S
                      backoff:
                        constant: {}
                    do:
                      - setHandled:
                          set:
                            handled: @{ @context }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the error was caught and handled after retries
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `error in try-catch with retry succeeds after retry`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        attempt: @{ .attempt + 1 }
                    - raise:
                        error: runtime
                        title: "Test Error"
                        when: @{ .attempt < 2 }
                  catch:
                    errors:
                      with:
                        type: runtime
                    retry:
                      limit:
                        attempt:
                          count: 3
                      delay: PT1S
                      backoff:
                        constant: true
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf("attempt" to JsonPrimitive(0))))

        // Run the workflow
        instance.run()

        // Verify the error was handled after successful retry
        assertEquals(
            JsonUtils.fromValue(mapOf("attempt" to 2)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `uncaught error faeults the workflow`() = runTest {
        val workflowYaml = """
do:
  - call-http:
      call: http
      wixth:
        method: GET
        endpoint: https://swapi.dev/api/people
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `uncaught error faults the workflow`() = runTest {
        val workflowYaml = """
            do:
              - raise:
                  error: runtime
                  title: "Uncaught Error"
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `error with when condition is only caught when condition is true`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - raise:
                        error: runtime
                        title: "Conditional Error"
                  catch:
                    errors:
                      with:
                        type: runtime
                    when: @{ .shouldCatch }
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf("shouldCatch" to JsonPrimitive(true))))

        // Run the workflow
        instance.run()

        // Verify the error was caught when condition was true
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `error with exceptWhen condition is not caught when condition is true`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - raise:
                        error: runtime
                        title: "Conditional Error"
                  catch:
                    errors:
                      with:
                        type: runtime
                    exceptWhen: @{ .shouldNotCatch }
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf("shouldNotCatch" to JsonPrimitive(true))))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted when exceptWhen condition is true
        assertEquals(WorkflowStatus.FAULTED, instance.status)
    }

    @Test
    fun `validation error in input schema is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        value: "invalid"
                    input:
                      schema:
                        type: object
                        properties:
                          value:
                            type: number
                  catch:
                    errors:
                      with:
                        type: validation
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the validation error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `validation error in output schema is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        value: "invalid"
                    output:
                      schema:
                        type: object
                        properties:
                          value:
                            type: number
                  catch:
                    errors:
                      with:
                        type: validation
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the validation error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `expression error in input transformation is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        value: @{ invalid.expression }
                  catch:
                    errors:
                      with:
                        type: expression
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the expression error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `flow error in invalid goto is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        value: "test"
                    then: "non_existent_node"
                  catch:
                    errors:
                      with:
                        type: configuration
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the flow error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `runtime error in async operation is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - callAsyncAPI:
                        operation: "invalid_operation"
                        parameters:
                          invalid: true
                  catch:
                    errors:
                      with:
                        type: runtime
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the runtime error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `multiple error types can be caught in same try-catch`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - set:
                        value: @{ invalid.expression }
                    input:
                      schema:
                        type: object
                        properties:
                          value:
                            type: number
                  catch:
                    errors:
                      with:
                        type: [expression, validation]
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `error with specific status code is caught`() = runTest {
        val workflowYaml = """
            do:
              - try:
                  do:
                    - raise:
                        error: runtime
                        title: "Specific Error"
                        status: 404
                  catch:
                    errors:
                      with:
                        type: runtime
                        status: 404
                    do:
                      - set:
                          handled: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the error was caught
        assertEquals(
            JsonUtils.fromValue(mapOf("handled" to true)),
            instance.rootInstance.transformedOutput
        )
    }

    @Test
    fun `validation error without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - set:
                  value: "invalid"
                input:
                  schema:
                    type: object
                    properties:
                      value:
                        type: number
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `expression error without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - set:
                  value: @{ invalid.expression }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `flow error without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - set:
                  value: "test"
                then: "non_existent_node"
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `runtime error without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - callAsyncAPI:
                  operation: "invalid_operation"
                  parameters:
                    invalid: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `error in nested node without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - do:
                  - set:
                      value: @{ invalid.expression }
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }

    @Test
    fun `error in parallel branches without try-catch faults workflow`() = runTest {
        val workflowYaml = """
            do:
              - fork:
                  - do:
                      - set:
                          value: @{ invalid.expression }
                  - do:
                      - set:
                          value: "valid"
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow is faulted
        assertEquals(WorkflowStatus.FAULTED, instance.status)
        assertNotNull(instance.currentNodeInstance)
    }
} 