// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectErrorContaining
import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import com.lemline.core.testcases.WorkflowTestValidators.expectSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for RunWorkflow (sub-workflow) execution.
 * Tests sub-workflow invocation, input/output handling, and async execution.
 */
object RunWorkflowTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "workflow can execute simple sub-workflow",
            yaml = """
                do:
                  - callDoubler:
                      run:
                        workflow:
                          namespace: test
                          name: doubler
                          version: '0.1.0'
                          input:
                            value: 5
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "doubler",
                    yaml = $$"""
                        do:
                          - double:
                              set:
                                result: ${ .value * 2 }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("result=10") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 10
            }
        ),

        WorkflowTestCase(
            name = "workflow can pass input to sub-workflow",
            yaml = $$"""
                do:
                  - setName:
                      set:
                        userName: Alice
                  - callGreeter:
                      run:
                        workflow:
                          namespace: test
                          name: greeter
                          version: '0.1.0'
                          input:
                            name: ${ .userName }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "greeter",
                    yaml = $$"""
                        do:
                          - greet:
                              set:
                                greeting: ${ "Hello, " + .name + "!" }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("greeting=Hello, Alice!") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["greeting"]?.jsonPrimitive?.content == "Hello, Alice!"
            }
        ),

        WorkflowTestCase(
            name = "workflow can execute recursive factorial calculation",
            input = JsonObject(mapOf("n" to JsonPrimitive(5))),
            yaml = $$"""
                do:
                  - checkBaseCase:
                      switch:
                        - base:
                            when: ${ .n == 1 }
                            then: returnOne
                        - default:
                            then: computeRecursive

                  - returnOne:
                      set:
                        n: 1
                      then: end

                  - computeRecursive:
                      do:
                        - callFactorialNMinus1:
                            run:
                              workflow:
                                namespace: test
                                name: factorial
                                version: '0.1.0'
                                input:
                                  n: ${ .n - 1 }
                        - multiplyResults:
                            set:
                              n: ${ .n * $workflow.input.n }
                            then: end
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "factorial",
                    yaml = $$"""
                        do:
                          - checkBaseCase:
                              switch:
                                - base:
                                    when: ${ .n == 1 }
                                    then: returnOne
                                - default:
                                    then: computeRecursive

                          - returnOne:
                              set:
                                n: 1
                              then: end

                          - computeRecursive:
                              do:
                                - callFactorialNMinus1:
                                    run:
                                      workflow:
                                        namespace: test
                                        name: factorial
                                        version: '0.1.0'
                                        input:
                                          n: ${ .n - 1 }
                                - multiplyResults:
                                    set:
                                      n: ${ .n * $workflow.input.n }
                                    then: end
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("n=120 (5!)") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["n"]?.jsonPrimitive?.int == 120
            }
        ),

        WorkflowTestCase(
            name = "workflow can call multiple sub-workflows in sequence",
            yaml = $$"""
                do:
                  - callAdder:
                      run:
                        workflow:
                          namespace: test
                          name: adder
                          version: '0.1.0'
                          input:
                            a: 3
                            b: 7
                  - callMultiplier:
                      run:
                        workflow:
                          namespace: test
                          name: multiplier
                          version: '0.1.0'
                          input:
                            value: ${ .result }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "adder",
                    yaml = $$"""
                        do:
                          - add:
                              set:
                                result: ${ .a + .b }
                    """.trimIndent()
                ),
                WorkflowDependency(
                    name = "multiplier",
                    yaml = $$"""
                        do:
                          - multiply:
                              set:
                                result: ${ .value * 2 }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("result=20 ((3+7)*2)") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 20
            }
        ),

        WorkflowTestCase(
            name = "workflow can use sub-workflow output in subsequent tasks",
            yaml = $$"""
                do:
                  - callProcessor:
                      run:
                        workflow:
                          namespace: test
                          name: processor
                          version: '0.1.0'
                          input:
                            input: 5
                  - useResult:
                      set:
                        finalValue: ${ .value + 10 }
                        wasProcessed: ${ .processed }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "processor",
                    yaml = $$"""
                        do:
                          - process:
                              set:
                                processed: true
                                value: ${ .input * 10 }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("finalValue=60, wasProcessed=true") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["finalValue"]?.jsonPrimitive?.int == 60 &&
                    obj["wasProcessed"]?.jsonPrimitive?.content == "true"
            }
        ),

        WorkflowTestCase(
            name = "workflow output can be transformed with output as",
            yaml = $$"""
                do:
                  - callChild:
                      run:
                        workflow:
                          namespace: test
                          name: child
                          version: '0.1.0'
                      output:
                        as: '${ {result: .value} }'
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "child",
                    yaml = """
                        do:
                          - compute:
                              set:
                                value: 42
                                status: success
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("result=42, no status") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 42 && !obj.containsKey("status")
            }
        ),

        WorkflowTestCase(
            name = "workflow can execute sub-workflow with complex input",
            yaml = """
                do:
                  - callProcessor:
                      run:
                        workflow:
                          namespace: test
                          name: person-processor
                          version: '0.1.0'
                          input:
                            firstName: John
                            lastName: Doe
                            age: 30
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "person-processor",
                    yaml = $$"""
                        do:
                          - process:
                              set:
                                fullName: ${ .firstName + " " + .lastName }
                                age: ${ .age }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("fullName=John Doe, age=30") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["fullName"]?.jsonPrimitive?.content == "John Doe" &&
                    obj["age"]?.jsonPrimitive?.int == 30
            }
        ),

        WorkflowTestCase(
            name = "workflow can chain multiple nested sub-workflows",
            yaml = """
                do:
                  - callLevel1:
                      run:
                        workflow:
                          namespace: test
                          name: level1
                          version: '0.1.0'
                          input:
                            value: 5
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "level3",
                    yaml = $$"""
                        do:
                          - compute:
                              set:
                                result: ${ .value + 1 }
                    """.trimIndent()
                ),
                WorkflowDependency(
                    name = "level2",
                    yaml = $$"""
                        do:
                          - callLevel3:
                              run:
                                workflow:
                                  namespace: test
                                  name: level3
                                  version: '0.1.0'
                                  input:
                                    value: ${ .value * 2 }
                    """.trimIndent()
                ),
                WorkflowDependency(
                    name = "level1",
                    yaml = $$"""
                        do:
                          - callLevel2:
                              run:
                                workflow:
                                  namespace: test
                                  name: level2
                                  version: '0.1.0'
                                  input:
                                    value: ${ .value + 3 }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("result=17 (((5+3)*2)+1)") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["result"]?.jsonPrimitive?.int == 17
            }
        ),

        WorkflowTestCase(
            name = "workflow can execute sub-workflow with default input",
            yaml = """
                do:
                  - callChild:
                      run:
                        workflow:
                          namespace: test
                          name: defaulter
                          version: '0.1.0'
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "defaulter",
                    yaml = """
                        do:
                          - setDefaults:
                              set:
                                value: 10
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("value=10") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["value"]?.jsonPrimitive?.int == 10
            }
        ),

        WorkflowTestCase(
            name = "workflow can execute sub-workflow asynchronously without waiting",
            yaml = """
                do:
                  - callAsync:
                      run:
                        await: false
                        workflow:
                          namespace: test
                          name: slow-workflow
                          version: '0.1.0'
                  - continueImmediately:
                      set:
                        continued: true
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "slow-workflow",
                    yaml = """
                        do:
                          - slowTask:
                              set:
                                completed: true
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("continued=true, no completed") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["continued"]?.jsonPrimitive?.content == "true" &&
                    !obj.containsKey("completed")
            }
        ),

        WorkflowTestCase(
            name = "workflow can use workflow context in sub-workflow calls",
            yaml = $$"""
                do:
                  - setContext:
                      set:
                        myValue: 15
                  - callChild:
                      run:
                        workflow:
                          namespace: test
                          name: context-user
                          version: '0.1.0'
                          input:
                            original: ${ .myValue }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "context-user",
                    yaml = $$"""
                        do:
                          - compute:
                              set:
                                doubled: ${ .original * 2 }
                                parentValue: ${ .original }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("doubled=30, parentValue=15") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["doubled"]?.jsonPrimitive?.int == 30 &&
                    obj["parentValue"]?.jsonPrimitive?.int == 15
            }
        ),

        WorkflowTestCase(
            name = "exception raised in sub-workflow should propagate to parent for sync execution",
            yaml = """
                do:
                  - callFailingChild:
                      run:
                        workflow:
                          namespace: test
                          name: failing-child
                          version: '0.1.0'
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "failing-child",
                    yaml = """
                        do:
                          - raiseError:
                              raise:
                                error:
                                  type: https://serverlessworkflow.io/errors/child
                                  status: 400
                                  title: Validation failed
                    """.trimIndent()
                )
            ),
            validate = expectErrorContaining("child")
        ),

        WorkflowTestCase(
            name = "exception raised in sub-workflow should not propagate to parent for async execution",
            yaml = """
                do:
                  - callFailingChild:
                      run:
                        await: false
                        workflow:
                          namespace: test
                          name: failing-child-async
                          version: '0.1.0'
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "failing-child-async",
                    yaml = """
                        do:
                          - raiseError:
                              raise:
                                error:
                                  type: https://serverlessworkflow.io/errors/child
                                  status: 400
                                  title: Validation failed
                    """.trimIndent()
                )
            ),
            validate = expectSuccess()
        ),

        WorkflowTestCase(
            name = "parent workflow can catch exception thrown by sub-workflow",
            yaml = $$"""
                do:
                  - tryCallChild:
                      try:
                        - callChild:
                            run:
                              workflow:
                                namespace: test
                                name: error-child
                                version: '0.1.0'
                      catch:
                        as: caught
                        do:
                          - handleError:
                              set:
                                handled: true
                                errorStatus: ${ $caught.status }
                                errorType: ${ $caught.type }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "error-child",
                    yaml = """
                        do:
                          - raiseError:
                              raise:
                                error:
                                  type: https://serverlessworkflow.io/errors/not-found
                                  status: 404
                                  title: Resource not found
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("handled=true, errorStatus=404") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["handled"]?.jsonPrimitive?.content == "true" &&
                    obj["errorStatus"]?.jsonPrimitive?.int == 404 &&
                    obj["errorType"]?.jsonPrimitive?.content?.contains("not-found") == true
            }
        ),

        WorkflowTestCase(
            name = "run workflow inside for loop executes multiple times with distinct results",
            yaml = $$"""
                do:
                  - init:
                      set:
                        results: []
                  - loopWithRunWorkflow:
                      for:
                        in: ${ [1, 2, 3] }
                      do:
                        - callMultiplier:
                            run:
                              workflow:
                                namespace: test
                                name: loop-item-multiplier
                                version: '0.1.0'
                                input:
                                  value: ${ $item }
                            export:
                              as:
                                results: ${ $context.results + [.result] }
                      output:
                        as: ${ $context }
            """.trimIndent(),
            dependencies = listOf(
                WorkflowDependency(
                    name = "loop-item-multiplier",
                    yaml = $$"""
                        do:
                          - multiply:
                              set:
                                result: ${ .value * 10 }
                    """.trimIndent()
                )
            ),
            validate = expectOutputMatching("3 iterations with values [10, 20, 30]") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                val results = obj["results"]?.jsonArray ?: return@expectOutputMatching false
                if (results.size != 3) return@expectOutputMatching false

                val values = results.mapNotNull { it.jsonPrimitive.int }
                values == listOf(10, 20, 30)
            }
        )
    )
}
