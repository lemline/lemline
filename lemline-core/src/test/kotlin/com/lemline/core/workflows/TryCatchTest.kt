package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import com.lemline.core.nodes.flows.TryInstance
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class TryCatchTest {

    @Test
    fun `check catch all`() = runTest {
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
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check caught by status`() = runTest {
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
                        status: 500
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `test not caught by status`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/errors/not-implemented
                            status: 501
                  catch:
                    errors:
                      with:
                        status: 500
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `check caught by type`() = runTest {
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
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check not caught by type`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/errors/other-error
                            status: 501
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/errors/not-implemented
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `check retry then continue`() = runTest {
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
                    retry:
                      limit:
                        attempt:
                          count: 2
                      delay: PT1S
                      backoff:
                        constant: {}
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        (instance.current as TryInstance).delay shouldBe 1.seconds
        (instance.current as TryInstance).attemptIndex shouldBe 1
        instance.status shouldBe WorkflowStatus.RUNNING
        println("Retrying...")
        // Run the workflow
        instance.run()

        (instance.current as TryInstance).delay shouldBe 1.seconds
        (instance.current as TryInstance).attemptIndex shouldBe 2
        instance.status shouldBe WorkflowStatus.RUNNING
        println("Retrying...")

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check retry then reach limit`() = runTest {
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
                    retry:
                      limit:
                        attempt:
                          count: 2
                      delay: PT1S
                      backoff:
                        constant: {}
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        (instance.current as TryInstance).delay shouldBe 1.seconds
        (instance.current as TryInstance).attemptIndex shouldBe 1
        instance.status shouldBe WorkflowStatus.RUNNING
        println("Retrying...")

        // Run the workflow
        instance.run()

        (instance.current as TryInstance).delay shouldBe 1.seconds
        (instance.current as TryInstance).attemptIndex shouldBe 2
        instance.status shouldBe WorkflowStatus.RUNNING
        println("Retrying...")

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `check when(true) in retry`() = runTest {
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
                    when: @error.status == 500
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check when(false) in retry`() = runTest {
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
                    when: @error.status == 400
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `check as-when(true) in retry`() = runTest {
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
                    as: issue
                    when: @issue.status == 500
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check as-when(false) in retry`() = runTest {
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
                    as: issue
                    when: @issue.status == 400
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `check exceptWhen(false) in retry`() = runTest {
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
                    exceptWhen: @error.status == 400
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `check exceptWhen(true) in retry`() = runTest {
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
                    exceptWhen: @error.status == 500
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `not targeted validation error in input schema is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        input:
                          schema:
                            format: json
                            document:
                              type: object
                              required:
                                - searchQuery
                              properties:
                                searchQuery:
                                  type: string
                        set:
                          value: "invalid"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf("firstName" to JsonPrimitive("Gilles"))))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted validation error in input schema is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        input:
                          schema:
                            format: json
                            document:
                              type: object
                              required:
                                - searchQuery
                              properties:
                                searchQuery:
                                  type: string
                        set:
                          value: "invalid"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf("firstName" to JsonPrimitive("Gilles"))))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `non targeted validation error in output schema is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        output:
                          schema:
                            format: json
                            document:
                              type: object
                              required:
                                - searchQuery
                              properties:
                                searchQuery:
                                  type: string
                        set:
                          value: "invalid"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted validation error in output schema is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        output:
                          schema:
                            format: json
                            document:
                              type: object
                              required:
                                - searchQuery
                              properties:
                                searchQuery:
                                  type: string
                        set:
                          value: "invalid"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `non targeted expression error in input transformation is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        input:
                          from: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted expression error in input transformation is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        input:
                          from: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `non targeted expression error in expression is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        set:
                          valid: @{ @invalid }
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted expression error in expression is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        set:
                          valid: @{ @invalid }
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `non targeted error in output transformation is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        output:
                          as: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted error in output transformation is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        output:
                          as: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }

    @Test
    fun `non targeted error in context export is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        export:
                          as: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted error in context export is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        export:
                          as: @invalid
                        set:
                          valid: true
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }


    @Test
    fun `non targeted flow error is not caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        set:
                          valid: true
                        then: non_existent_node
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/other
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.FAULTED
    }

    @Test
    fun `targeted flow error is caught`() = runTest {
        val workflowYaml = """
            do:
              - trySomething:
                  try:
                    - setInvalid:
                        set:
                          valid: true
                        then: non_existent_node
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/configuration
                    do:
                      - setCaught:
                          set:
                            caught: true
        """
        val instance = getWorkflowInstance(workflowYaml, JsonObject(mapOf()))

        // Run the workflow
        instance.run()

        // Verify the workflow status
        instance.status shouldBe WorkflowStatus.COMPLETED
    }
} 