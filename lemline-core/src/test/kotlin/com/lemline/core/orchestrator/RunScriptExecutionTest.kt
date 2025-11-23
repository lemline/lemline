// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integration tests for Script execution using CompleteOrchestrator.
 *
 * Tests script execution to verify:
 * - JavaScript and Python script execution
 * - Inline code execution
 * - Arguments and environment variables
 * - Different return types (stdout, stderr, code, all, none)
 * - Expression evaluation in scripts
 * - Integration with workflow context
 */
@ExperimentalTime
class RunScriptExecutionTest : FunSpec() {

    init {
        test("script can execute simple JavaScript").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - runJsScript:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('Hello from JavaScript');
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello from JavaScript", (output as JsonPrimitive).content)
        }

        test("script can execute simple Python").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - runPyScript:
                      run:
                        script:
                          language: python
                          code: |
                            print('Hello from Python')
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello from Python", (output as JsonPrimitive).content)
        }

        test("script can use arguments in JavaScript").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - runWithArgs:
                      run:
                        script:
                          language: js
                          code: |
                            let name = 'World';
                            for (let i = 0; i < process.argv.length - 1; i++) {
                                if (process.argv[i] === '--name') {
                                    name = process.argv[i + 1];
                                    break;
                                }
                            }
                            console.log('Hello, ' + name + '!');
                          arguments:
                            "--name": Alice
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello, Alice!", (output as JsonPrimitive).content)
        }

        test("script can use arguments in Python").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - runWithArgs:
                      run:
                        script:
                          language: python
                          code: |
                            import sys
                            name = 'World'
                            for i in range(len(sys.argv) - 1):
                                if sys.argv[i] == '--name':
                                    name = sys.argv[i + 1]
                                    break
                            print(f'Hello, {name}!')
                          arguments:
                            "--name": Bob
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello, Bob!", (output as JsonPrimitive).content)
        }

        test("script can use environment variables in JavaScript").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - useEnv:
                      run:
                        script:
                          language: js
                          code: |
                            console.log(process.env.MY_VAR || 'default');
                          environment:
                            MY_VAR: TestValue
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("TestValue", (output as JsonPrimitive).content)
        }

        test("script can use environment variables in Python").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - useEnv:
                      run:
                        script:
                          language: python
                          code: |
                            import os
                            print(os.environ.get('MY_VAR', 'default'))
                          environment:
                            MY_VAR: PythonValue
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("PythonValue", (output as JsonPrimitive).content)
        }

        test("script can return stdout explicitly").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - returnStdout:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('This is stdout');
                        return: stdout
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("This is stdout", (output as JsonPrimitive).content)
        }

        test("script can return stderr").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - returnStderr:
                      run:
                        script:
                          language: js
                          code: |
                            console.error('This is stderr');
                        return: stderr
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("This is stderr", (output as JsonPrimitive).content)
        }

        test("script can return exit code").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - returnCode:
                      run:
                        script:
                          language: js
                          code: |
                            process.exit(42);
                        return: code
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals(42, (output as JsonPrimitive).int)
        }

        test("script can return all outputs").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - returnAll:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('stdout message');
                            console.error('stderr message');
                            process.exit(5);
                        return: all
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("stdout message", output["stdout"]?.jsonPrimitive?.content)
            assertEquals("stderr message", output["stderr"]?.jsonPrimitive?.content)
            assertEquals(5, output["code"]?.jsonPrimitive?.int)
        }

        test("script can use expressions in code").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - setData:
                      set:
                        greeting: Hello
                        name: Charlie
                  - runWithExpression:
                      run:
                        script:
                          language: js
                          code: ${ "console.log('" + .greeting + ", " + .name + "!');" }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello, Charlie!", (output as JsonPrimitive).content)
        }

        test("script can use expressions in arguments").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - setName:
                      set:
                        userName: Diana
                  - runWithExprArgs:
                      run:
                        script:
                          language: js
                          code: |
                            let name = 'World';
                            for (let i = 0; i < process.argv.length - 1; i++) {
                                if (process.argv[i] === '--name') {
                                    name = process.argv[i + 1];
                                    break;
                                }
                            }
                            console.log('Hello, ' + name + '!');
                          arguments:
                            "--name": ${ .userName }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("Hello, Diana!", (output as JsonPrimitive).content)
        }

        test("script can use expressions in environment variables").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - setValue:
                      set:
                        envValue: FromContext
                  - runWithExprEnv:
                      run:
                        script:
                          language: js
                          code: |
                            console.log(process.env.MY_VAR || 'default');
                          environment:
                            MY_VAR: ${ .envValue }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("FromContext", (output as JsonPrimitive).content)
        }

        test("script can chain with other tasks").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - runScript:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('ScriptData');
                  - processData:
                      set:
                        result: ${ . }
                        hasResult: true
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("ScriptData", output["result"]?.jsonPrimitive?.content)
            assertEquals(true, output["hasResult"]?.jsonPrimitive?.content?.toBoolean())
        }

        test("script output can be transformed with output as").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - runAndTransform:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('test output');
                      output:
                        as: '${ {scriptResult: .} }'
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("test output", output["scriptResult"]?.jsonPrimitive?.content)
        }

        test("script can execute multiple scripts in sequence").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = $$"""
                do:
                  - firstScript:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('First');
                  - secondScript:
                      run:
                        script:
                          language: python
                          code: |
                            print('Second')
                  - combine:
                      set:
                        result: ${ . }
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap())) as JsonObject

            assertEquals("Second", output["result"]?.jsonPrimitive?.content)
        }

        test("script can perform computations in JavaScript").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - compute:
                      run:
                        script:
                          language: js
                          code: |
                            const result = 2 + 2;
                            console.log(result);
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("4", (output as JsonPrimitive).content)
        }

        test("script can perform computations in Python").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - compute:
                      run:
                        script:
                          language: python
                          code: |
                            result = 3 * 7
                            print(result)
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            assertEquals("21", (output as JsonPrimitive).content)
        }

        test("script can handle multi-line output in JavaScript").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - multiLine:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('Line 1');
                            console.log('Line 2');
                            console.log('Line 3');
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            val result = (output as JsonPrimitive).content
            assertTrue(result.contains("Line 1"))
            assertTrue(result.contains("Line 2"))
            assertTrue(result.contains("Line 3"))
        }

        test("script can handle multi-line output in Python").config(enabledIf = {
            System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("linux") }
        }) {
            val yaml = """
                do:
                  - multiLine:
                      run:
                        script:
                          language: python
                          code: |
                            print('Line 1')
                            print('Line 2')
                            print('Line 3')
            """
            val output = executeWorkflow(yaml, JsonObject(emptyMap()))

            val result = (output as JsonPrimitive).content
            assertTrue(result.contains("Line 1"))
            assertTrue(result.contains("Line 2"))
            assertTrue(result.contains("Line 3"))
        }
    }
}
