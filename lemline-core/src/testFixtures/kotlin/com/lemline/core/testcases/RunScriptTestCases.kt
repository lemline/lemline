// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for Script execution.
 * Tests JavaScript and Python script execution using mocks.
 *
 * Note: These tests use mocked script execution. Real script execution tests
 * should be in the runner module with actual script interpreters.
 */
object RunScriptTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "script can execute simple JavaScript",
            mockConfig = TestMocks.scriptConfig,
            yaml = """
                do:
                  - runJsScript:
                      run:
                        script:
                          language: js
                          code: |
                            console.log('Hello from JavaScript');
            """.trimIndent(),
            tags = setOf("script", "js"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can execute simple Python",
            mockConfig = TestMocks.scriptConfig,
            yaml = """
                do:
                  - runPyScript:
                      run:
                        script:
                          language: python
                          code: |
                            print('Hello from Python')
            """.trimIndent(),
            tags = setOf("script", "python"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can use arguments in JavaScript",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can use arguments in Python",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "python"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can use environment variables in JavaScript",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can use environment variables in Python",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "python"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can return stdout explicitly",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("string output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can chain with other tasks",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("result set with script output") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("result") && obj["hasResult"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "script output can be transformed with output as",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("transformed output with scriptResult") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("scriptResult")
            }
        ),

        WorkflowTestCase(
            name = "script can execute multiple scripts in sequence",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script"),
            validate = expectOutputMatching("result from last script") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("result")
            }
        ),

        WorkflowTestCase(
            name = "script can perform computations in JavaScript",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("computation result") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can perform computations in Python",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "python"),
            validate = expectOutputMatching("computation result") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can handle multi-line output in JavaScript",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "js"),
            validate = expectOutputMatching("multi-line output") { output ->
                output is JsonPrimitive
            }
        ),

        WorkflowTestCase(
            name = "script can handle multi-line output in Python",
            mockConfig = TestMocks.scriptConfig,
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
            tags = setOf("script", "python"),
            validate = expectOutputMatching("multi-line output") { output ->
                output is JsonPrimitive
            }
        )
    )
}
