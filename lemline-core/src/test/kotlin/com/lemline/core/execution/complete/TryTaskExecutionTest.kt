// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.errors.WorkflowException
import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for TryTask execution using CompleteOrchestrator.
 *
 * Tests error handling with try/catch blocks, retry logic, and error filtering.
 */
@ExperimentalTime
class TryTaskExecutionTest {

    @Test
    fun `try catch - basic error is caught`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            caught: true
                            errorHandled: "yes"
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        assertEquals("yes", output["errorHandled"]?.jsonPrimitive?.content)
    }

    @Test
    fun `try catch - unhandled error propagates when no matching catch`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }

    @Test
    fun `try catch - error type filtering matches`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                            status: 400
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - error type filtering does not match`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }

    @Test
    fun `try catch - error status filtering matches`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    errors:
                      with:
                        status: 500
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - error status filtering does not match`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    errors:
                      with:
                        status: 404
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }

    @Test
    fun `try catch - error data is accessible in catch block`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            errorType: ${ $error.type }
                            errorStatus: ${ $error.status }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(
            "https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
            output["errorType"]?.jsonPrimitive?.content
        )
        assertEquals(500, output["errorStatus"]?.jsonPrimitive?.int)
    }

    @Test
    fun `try catch - custom error variable name`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    as: issue
                    do:
                      - handleError:
                          set:
                            issueType: ${ $issue.type }
                            issueStatus: ${ $issue.status }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(
            "https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
            output["issueType"]?.jsonPrimitive?.content
        )
        assertEquals(500, output["issueStatus"]?.jsonPrimitive?.int)
    }

    @Test
    fun `try catch - when condition matches`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    when: ${ $error.status == 500 }
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - when condition does not match`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    when: ${ $error.status == 404 }
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }

    @Test
    fun `try catch - exceptWhen condition excludes error`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    exceptWhen: ${ $error.status == 500 }
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }

    @Test
    fun `try catch - exceptWhen condition allows error`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    exceptWhen: ${ $error.status == 404 }
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - successful try block does not enter catch`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - successTask:
                        set:
                          success: true
                  catch:
                    do:
                      - handleError:
                          set:
                            caught: true
              - afterTry:
                  set:
                    final: ${ .success }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["final"]?.jsonPrimitive?.boolean)
        assertEquals(null, output["caught"])  // Should not have entered catch
    }

    @Test
    fun `try catch - input transformation error is caught`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - badTransform:
                        input:
                          from: "${ invalid syntax [ }"
                        set:
                          value: "should not reach here"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - validation error is caught`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - validateTask:
                        input:
                          schema:
                            format: json
                            document:
                              type: object
                              required:
                                - requiredField
                              properties:
                                requiredField:
                                  type: string
                        set:
                          value: "should not reach here"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - error in nested task is caught by outer try`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - outerTask:
                        do:
                          - innerTask:
                              do:
                                - deepTask:
                                    raise:
                                      error:
                                        type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                                        status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - state is properly cleaned on retry`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - step1:
                        set:
                          step: 1
                    - step2:
                        set:
                          step: 2
                    - failingStep:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            caught: true
                            # If state wasn't cleaned, we'd still have step1 and step2 data
                            hasStep: ${ .step // "none" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        // The catch block should NOT have access to step1/step2 data
        // because state was cleaned when entering catch
        assertEquals("none", output["hasStep"]?.jsonPrimitive?.content)
    }

    @Test
    fun `try catch - original input is preserved in catch block`() = runTest {
        val yaml = $$"""
            do:
              - prepareData:
                  set:
                    initialValue: 42
              - trySomething:
                  try:
                    - modifyData:
                        set:
                          modifiedValue: 100
                    - failingStep:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - handleError:
                          set:
                            # Should have access to input from before try block
                            receivedValue: ${ .initialValue }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(42, output["receivedValue"]?.jsonPrimitive?.int)
    }

    @Test
    fun `try catch - multiple tasks in catch block execute sequentially`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                            status: 500
                  catch:
                    do:
                      - step1:
                          set:
                            step1: "done"
                      - step2:
                          set:
                            step2: "done"
                      - step3:
                          set:
                            step3: "done"
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("done", output["step1"]?.jsonPrimitive?.content)
        assertEquals("done", output["step2"]?.jsonPrimitive?.content)
        assertEquals("done", output["step3"]?.jsonPrimitive?.content)
    }

    @Test
    fun `try catch - output transformation error is caught`() = runTest {
        val yaml = $$"""
            do:
              - trySomething:
                  try:
                    - badOutput:
                        output:
                          as: "${ invalid syntax [ }"
                        set:
                          value: "test"
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/expression
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - combined type and status filtering`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                            status: 400
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                        status: 400
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `try catch - combined filtering with one mismatch fails`() = runTest {
        val yaml = """
            do:
              - trySomething:
                  try:
                    - raiseError:
                        raise:
                          error:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                            status: 400
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
                        status: 404
                    do:
                      - handleError:
                          set:
                            caught: true
        """
        val rootNode = getWorkflowNode(yaml)

        assertFailsWith<WorkflowException> {
            CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))
        }
    }
}
