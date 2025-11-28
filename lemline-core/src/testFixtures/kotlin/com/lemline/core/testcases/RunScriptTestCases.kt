// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for Script execution.
 * Tests JavaScript and Python script execution with various configurations.
 */
object RunScriptTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "script can execute simple JavaScript",
            yaml = """
                do:
                  - runJsScript:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('Hello from JavaScript');
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("Hello from JavaScript") { output ->
                (output as? JsonPrimitive)?.content == "Hello from JavaScript"
            }
        ),

        WorkflowTestCase(
            name = "script can execute simple Python",
            yaml = """
                do:
                  - runPyScript:
                      run:
                        script:
                          language: python
                          code: |
                            print('Hello from Python')
            """.trimIndent(),
            tags = setOf("unix-only", "script", "python"),
            validate = expectOutputMatching("Hello from Python") { output ->
                (output as? JsonPrimitive)?.content == "Hello from Python"
            }
        ),

        WorkflowTestCase(
            name = "script can use arguments in JavaScript",
            yaml = """
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("Hello, Alice!") { output ->
                (output as? JsonPrimitive)?.content == "Hello, Alice!"
            }
        ),

        WorkflowTestCase(
            name = "script can use arguments in Python",
            yaml = """
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "python"),
            validate = expectOutputMatching("Hello, Bob!") { output ->
                (output as? JsonPrimitive)?.content == "Hello, Bob!"
            }
        ),

        WorkflowTestCase(
            name = "script can use environment variables in JavaScript",
            yaml = """
                do:
                  - useEnv:
                      run:
                        script:
                          language: js
                          code: |
                            console.log(process.env.MY_VAR || 'default');
                          environment:
                            MY_VAR: TestValue
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("TestValue") { output ->
                (output as? JsonPrimitive)?.content == "TestValue"
            }
        ),

        WorkflowTestCase(
            name = "script can use environment variables in Python",
            yaml = """
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "python"),
            validate = expectOutputMatching("PythonValue") { output ->
                (output as? JsonPrimitive)?.content == "PythonValue"
            }
        ),

        WorkflowTestCase(
            name = "script can return stdout explicitly",
            yaml = """
                do:
                  - returnStdout:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('This is stdout');
                        return: stdout
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("This is stdout") { output ->
                (output as? JsonPrimitive)?.content == "This is stdout"
            }
        ),

        WorkflowTestCase(
            name = "script can return stderr",
            yaml = """
                do:
                  - returnStderr:
                      run:
                        script:
                          language: js
                          code: |
                            console.error('This is stderr');
                        return: stderr
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("This is stderr") { output ->
                (output as? JsonPrimitive)?.content == "This is stderr"
            }
        ),

        WorkflowTestCase(
            name = "script can return exit code",
            yaml = """
                do:
                  - returnCode:
                      run:
                        script:
                          language: js
                          code: |
                            process.exit(42);
                        return: code
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("exit code 42") { output ->
                (output as? JsonPrimitive)?.int == 42
            }
        ),

        WorkflowTestCase(
            name = "script can return all outputs",
            yaml = """
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("stdout, stderr, and code") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["stdout"]?.jsonPrimitive?.content == "stdout message" &&
                    obj["stderr"]?.jsonPrimitive?.content == "stderr message" &&
                    obj["code"]?.jsonPrimitive?.int == 5
            }
        ),

        WorkflowTestCase(
            name = "script can use expressions in code",
            yaml = $$"""
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("Hello, Charlie!") { output ->
                (output as? JsonPrimitive)?.content == "Hello, Charlie!"
            }
        ),

        WorkflowTestCase(
            name = "script can use expressions in arguments",
            yaml = $$"""
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("Hello, Diana!") { output ->
                (output as? JsonPrimitive)?.content == "Hello, Diana!"
            }
        ),

        WorkflowTestCase(
            name = "script can use expressions in environment variables",
            yaml = $$"""
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("FromContext") { output ->
                (output as? JsonPrimitive)?.content == "FromContext"
            }
        ),

        WorkflowTestCase(
            name = "script can chain with other tasks",
            yaml = $$"""
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
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("result=ScriptData, hasResult=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "ScriptData" &&
                    obj["hasResult"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "script output can be transformed with output as",
            yaml = $$"""
                do:
                  - runAndTransform:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('test output');
                      output:
                        as: '${ {scriptResult: .} }'
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("scriptResult=test output") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["scriptResult"]?.jsonPrimitive?.content == "test output"
            }
        ),

        WorkflowTestCase(
            name = "script can execute multiple scripts in sequence",
            yaml = $$"""
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
            """.trimIndent(),
            tags = setOf("unix-only", "script"),
            validate = expectOutputMatching("result=Second") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.content == "Second"
            }
        ),

        WorkflowTestCase(
            name = "script can perform computations in JavaScript",
            yaml = """
                do:
                  - compute:
                      run:
                        script:
                          language: js
                          code: |
                            const result = 2 + 2;
                            console.log(result);
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("4") { output ->
                (output as? JsonPrimitive)?.content == "4"
            }
        ),

        WorkflowTestCase(
            name = "script can perform computations in Python",
            yaml = """
                do:
                  - compute:
                      run:
                        script:
                          language: python
                          code: |
                            result = 3 * 7
                            print(result)
            """.trimIndent(),
            tags = setOf("unix-only", "script", "python"),
            validate = expectOutputMatching("21") { output ->
                (output as? JsonPrimitive)?.content == "21"
            }
        ),

        WorkflowTestCase(
            name = "script can handle multi-line output in JavaScript",
            yaml = """
                do:
                  - multiLine:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('Line 1');
                            console.log('Line 2');
                            console.log('Line 3');
            """.trimIndent(),
            tags = setOf("unix-only", "script", "js"),
            validate = expectOutputMatching("Line 1, Line 2, Line 3") { output ->
                val content = (output as? JsonPrimitive)?.content ?: return@expectOutputMatching false
                content.contains("Line 1") && content.contains("Line 2") && content.contains("Line 3")
            }
        ),

        WorkflowTestCase(
            name = "script can handle multi-line output in Python",
            yaml = """
                do:
                  - multiLine:
                      run:
                        script:
                          language: python
                          code: |
                            print('Line 1')
                            print('Line 2')
                            print('Line 3')
            """.trimIndent(),
            tags = setOf("unix-only", "script", "python"),
            validate = expectOutputMatching("Line 1, Line 2, Line 3") { output ->
                val content = (output as? JsonPrimitive)?.content ?: return@expectOutputMatching false
                content.contains("Line 1") && content.contains("Line 2") && content.contains("Line 3")
            }
        )
    )
}
