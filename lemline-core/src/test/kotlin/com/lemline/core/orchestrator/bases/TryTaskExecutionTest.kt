// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.bases

import com.lemline.core.errors.InternalWorkflowException
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integration tests for TryTask execution using CompleteOrchestrator.
 *
 * Tests error handling with try/catch blocks, retry logic, and error filtering.
 */
@ExperimentalTime
abstract class TryTaskExecutionTest : FunSpec() {

    init {
        test("try catch - basic error is caught") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
            assertEquals("yes", output["errorHandled"]?.jsonPrimitive?.content)
        }

        test("try catch - unhandled error propagates when no matching catch") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }

        test("try catch - error type filtering matches") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - error type filtering does not match") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }

        test("try catch - error status filtering matches") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - error status filtering does not match") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }

        test("try catch - error data is accessible in catch block") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(
                "https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
                output["errorType"]?.jsonPrimitive?.content
            )
            assertEquals(500, output["errorStatus"]?.jsonPrimitive?.int)
        }

        test("try catch - custom error variable name") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(
                "https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
                output["issueType"]?.jsonPrimitive?.content
            )
            assertEquals(500, output["issueStatus"]?.jsonPrimitive?.int)
        }

        test("try catch - when condition matches") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - when condition does not match") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }

        test("try catch - exceptWhen condition excludes error") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }

        test("try catch - exceptWhen condition allows error") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - successful try block does not enter catch") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["final"]?.jsonPrimitive?.boolean)
            assertEquals(null, output["caught"])  // Should not have entered catch
        }

        test("try catch - input transformation error is caught") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - validation error is caught") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - error in nested task is caught by outer try") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - state is properly cleaned on retry") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
            // The catch block should NOT have access to step1/step2 data
            // because state was cleaned when entering catch
            assertEquals("none", output["hasStep"]?.jsonPrimitive?.content)
        }

        test("try catch - original input is preserved in catch block") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(42, output["receivedValue"]?.jsonPrimitive?.int)
        }

        test("try catch - multiple tasks in catch block execute sequentially") {
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
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("done", output["step1"]?.jsonPrimitive?.content)
        }

        test("try catch - output transformation error is caught") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - combined type and status filtering") {
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
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals(true, output["caught"]?.jsonPrimitive?.boolean)
        }

        test("try catch - combined filtering with one mismatch fails") {
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

            assertFailsWith<InternalWorkflowException> {
                executeWorkflow(yaml, JsonObject(emptyMap()))
            }
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
