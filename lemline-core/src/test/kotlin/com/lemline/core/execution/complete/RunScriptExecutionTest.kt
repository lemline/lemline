// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

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
class RunScriptExecutionTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can execute simple JavaScript`() = runTest {
        val yaml = $"""
            do:
              - runJsScript:
                  run:
                    script:
                      language: js
                      code: |
                        console.log('Hello from JavaScript');
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello from JavaScript", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can execute simple Python`() = runTest {
        val yaml = $"""
            do:
              - runPyScript:
                  run:
                    script:
                      language: python
                      code: |
                        print('Hello from Python')
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello from Python", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use arguments in JavaScript`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello, Alice!", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use arguments in Python`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello, Bob!", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use environment variables in JavaScript`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("TestValue", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use environment variables in Python`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("PythonValue", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can return stdout explicitly`() = runTest {
        val yaml = $"""
            do:
              - returnStdout:
                  run:
                    script:
                      language: js
                      code: |
                        console.log('This is stdout');
                    return: stdout
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("This is stdout", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can return stderr`() = runTest {
        val yaml = $"""
            do:
              - returnStderr:
                  run:
                    script:
                      language: js
                      code: |
                        console.error('This is stderr');
                    return: stderr
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("This is stderr", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can return exit code`() = runTest {
        val yaml = $"""
            do:
              - returnCode:
                  run:
                    script:
                      language: js
                      code: |
                        process.exit(42);
                    return: code
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals(42, (output as JsonPrimitive).int)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can return all outputs`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("stdout message", output["stdout"]?.jsonPrimitive?.content)
        assertEquals("stderr message", output["stderr"]?.jsonPrimitive?.content)
        assertEquals(5, output["code"]?.jsonPrimitive?.int)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use expressions in code`() = runTest {
        val yaml = """
            do:
              - setData:
                  set:
                    greeting: Hello
                    name: Charlie
              - runWithExpression:
                  run:
                    script:
                      language: js
                      code: ${'$'}{ "console.log('" + .greeting + ", " + .name + "!');" }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello, Charlie!", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use expressions in arguments`() = runTest {
        val yaml = """
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
                        "--name": ${'$'}{ .userName }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("Hello, Diana!", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can use expressions in environment variables`() = runTest {
        val yaml = """
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
                        MY_VAR: ${'$'}{ .envValue }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("FromContext", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can chain with other tasks`() = runTest {
        val yaml = """
            do:
              - runScript:
                  run:
                    script:
                      language: js
                      code: |
                        console.log('ScriptData');
              - processData:
                  set:
                    result: ${'$'}{ . }
                    hasResult: true
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("ScriptData", output["result"]?.jsonPrimitive?.content)
        assertEquals(true, output["hasResult"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script output can be transformed with output as`() = runTest {
        val yaml = """
            do:
              - runAndTransform:
                  run:
                    script:
                      language: js
                      code: |
                        console.log('test output');
                  output:
                    as: '${'$'}{ {scriptResult: .} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("test output", output["scriptResult"]?.jsonPrimitive?.content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can execute multiple scripts in sequence`() = runTest {
        val yaml = """
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
                    result: ${'$'}{ . }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals("Second", output["result"]?.jsonPrimitive?.content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can perform computations in JavaScript`() = runTest {
        val yaml = $"""
            do:
              - compute:
                  run:
                    script:
                      language: js
                      code: |
                        const result = 2 + 2;
                        console.log(result);
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("4", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can perform computations in Python`() = runTest {
        val yaml = $"""
            do:
              - compute:
                  run:
                    script:
                      language: python
                      code: |
                        result = 3 * 7
                        print(result)
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        assertEquals("21", (output as JsonPrimitive).content)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can handle multi-line output in JavaScript`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        val result = (output as JsonPrimitive).content
        assertTrue(result.contains("Line 1"))
        assertTrue(result.contains("Line 2"))
        assertTrue(result.contains("Line 3"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `script can handle multi-line output in Python`() = runTest {
        val yaml = $"""
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
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        val result = (output as JsonPrimitive).content
        assertTrue(result.contains("Line 1"))
        assertTrue(result.contains("Line 2"))
        assertTrue(result.contains("Line 3"))
    }
}
