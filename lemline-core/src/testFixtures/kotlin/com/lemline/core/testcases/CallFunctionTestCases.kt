// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SetTaskConfiguration
import io.serverlessworkflow.api.types.Task
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test cases for function call execution.
 *
 * These tests verify that function calls actually execute the function's task
 * definition from `use.functions`. The function execution uses the same
 * orchestrator infrastructure as regular workflows.
 */
object CallFunctionTestCases {

    val cases = listOf(
        // ─────────────────────────────────────────────────────────────────────
        // Basic Function Execution Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function call executes set task from use.functions",
            yaml = """
                use:
                  functions:
                    getGreeting:
                      set:
                        message: "Hello, World!"
                        source: "greeting-function"
                do:
                  - greet:
                      call: getGreeting
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("message", "Hello, World!")
                    put("source", "greeting-function")
                }
            )
        ),

        WorkflowTestCase(
            name = "function call passes arguments to function task",
            yaml = $$"""
                use:
                  functions:
                    formatName:
                      set:
                        fullName: "${ .firstName + \" \" + .lastName }"
                do:
                  - format:
                      call: formatName
                      with:
                        firstName: John
                        lastName: Doe
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("fullName", "John Doe")
                }
            )
        ),

        WorkflowTestCase(
            name = "function call with expression arguments",
            yaml = $$"""
                use:
                  functions:
                    doubleValue:
                      set:
                        result: "${ .value * 2 }"
                do:
                  - double:
                      call: doubleValue
                      with:
                        value: "${ .input }"
            """.trimIndent(),
            input = buildJsonObject {
                put("input", 21)
            },
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("result", 42)
                }
            )
        ),

        WorkflowTestCase(
            name = "function call without arguments uses empty input",
            yaml = """
                use:
                  functions:
                    getConstant:
                      set:
                        value: 42
                        type: "constant"
                do:
                  - getConst:
                      call: getConstant
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("value", 42)
                    put("type", "constant")
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Function Output Transformation Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function call result can be transformed with output.as",
            yaml = $$"""
                use:
                  functions:
                    getUser:
                      set:
                        id: 123
                        name: "Alice"
                        email: "alice@example.com"
                do:
                  - user:
                      call: getUser
                      output:
                        as: '${ {userId: .id, displayName: .name} }'
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("userId", 123)
                    put("displayName", "Alice")
                }
            )
        ),

        WorkflowTestCase(
            name = "function call can export to context",
            yaml = $$"""
                use:
                  functions:
                    getConfig:
                      set:
                        environment: "production"
                        version: "1.0.0"
                do:
                  - loadConfig:
                      call: getConfig
                      export:
                        as: '${ {env: .environment} }'
                  - useConfig:
                      set:
                        deployEnv: "${ $context.env }"
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("deployEnv", "production")
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Chaining and Composition Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function calls can be chained",
            yaml = $$"""
                use:
                  functions:
                    step1:
                      set:
                        value: 10
                    step2:
                      set:
                        doubled: "${ .value * 2 }"
                do:
                  - first:
                      call: step1
                  - second:
                      call: step2
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("doubled", 20)
                }
            )
        ),

        WorkflowTestCase(
            name = "function can contain do block with multiple steps",
            yaml = """
                use:
                  functions:
                    processOrder:
                      do:
                        - validate:
                            set:
                              validated: true
                        - calculate:
                            set:
                              total: 100.50
                do:
                  - process:
                      call: processOrder
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("total", 100.50)
                }
            )
        ),

        WorkflowTestCase(
            name = "function result flows to next step",
            yaml = $$"""
                use:
                  functions:
                    getPrice:
                      set:
                        price: 99.99
                do:
                  - fetchPrice:
                      call: getPrice
                  - applyDiscount:
                      set:
                        discountedPrice: "${ .price * 0.9 }"
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("discountedPrice", 89.991)
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Complex Nested Execution Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function can call another function (nested)",
            yaml = $$"""
                use:
                  functions:
                    innerFunc:
                      set:
                        innerResult: "from inner"
                    outerFunc:
                      do:
                        - callInner:
                            call: innerFunc
                        - wrapResult:
                            set:
                              wrapped: "${ .innerResult }"
                              outerAdded: true
                do:
                  - callOuter:
                      call: outerFunc
            """.trimIndent(),
            tags = setOf("function", "execution", "nested"),
            validate = expectOutput(
                buildJsonObject {
                    put("wrapped", "from inner")
                    put("outerAdded", true)
                }
            )
        ),

        WorkflowTestCase(
            name = "multiple functions can be defined and called independently",
            yaml = $$"""
                use:
                  functions:
                    funcA:
                      set:
                        a: 1
                    funcB:
                      set:
                        b: 2
                    funcC:
                      set:
                        c: 3
                do:
                  - callA:
                      call: funcA
                      export:
                        as: '${ {resultA: .a} }'
                  - callB:
                      call: funcB
                      export:
                        as: '${ {resultB: .b} }'
                  - callC:
                      call: funcC
                      export:
                        as: '${ {resultC: .c} }'
                  - combine:
                      set:
                        sum: "${ $context.resultA + $context.resultB + $context.resultC }"
            """.trimIndent(),
            tags = setOf("function", "execution"),
            validate = expectOutput(
                buildJsonObject {
                    put("sum", 6)
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Remote Function Tests (URL-based)
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function call from URL executes remote function definition",
            yaml = """
                do:
                  - callRemote:
                      call: https://example.com/functions/greeting.yaml
            """.trimIndent(),
            tags = setOf("function", "remote", "url"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "https://example.com/functions/greeting.yaml" to createSetTask(
                        mapOf(
                            "message" to "Hello from remote!",
                            "source" to "url-function"
                        )
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("message", "Hello from remote!")
                    put("source", "url-function")
                }
            )
        ),

        WorkflowTestCase(
            name = "function call from URL with arguments",
            yaml = """
                do:
                  - callRemote:
                      call: https://api.example.com/functions/multiply.yaml
                      with:
                        x: 6
                        y: 7
            """.trimIndent(),
            tags = setOf("function", "remote", "url"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "https://api.example.com/functions/multiply.yaml" to createSetTask(
                        mapOf("result" to $$"${ .x * .y }")
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("result", 42)
                }
            )
        ),

        WorkflowTestCase(
            name = "function call from URL with expression arguments",
            yaml = $$"""
                do:
                  - callRemote:
                      call: https://example.com/functions/process.yaml
                      with:
                        value: "${ .input.number }"
                        label: "${ .input.name }"
            """.trimIndent(),
            input = buildJsonObject {
                put("input", buildJsonObject {
                    put("number", 100)
                    put("name", "test-item")
                })
            },
            tags = setOf("function", "remote", "url"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "https://example.com/functions/process.yaml" to createSetTask(
                        mapOf(
                            "processed" to true,
                            "doubledValue" to $$"${ .value * 2 }",
                            "itemLabel" to $$"${ .label }"
                        )
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("processed", true)
                    put("doubledValue", 200)
                    put("itemLabel", "test-item")
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Remote Function Tests (Catalog-based)
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function call from catalog executes cataloged function",
            yaml = """
                do:
                  - callCatalog:
                      call: formatCurrency:1.0.0@finance-utils
            """.trimIndent(),
            tags = setOf("function", "remote", "catalog"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "formatCurrency:1.0.0@finance-utils" to createSetTask(
                        mapOf(
                            "formatted" to "$0.00",
                            "catalog" to "finance-utils",
                            "version" to "1.0.0"
                        )
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("formatted", "$0.00")
                    put("catalog", "finance-utils")
                    put("version", "1.0.0")
                }
            )
        ),

        WorkflowTestCase(
            name = "function call from catalog with arguments",
            yaml = """
                do:
                  - callCatalog:
                      call: calculateTax:2.1.0@tax-catalog
                      with:
                        amount: 100.00
                        rate: 0.08
            """.trimIndent(),
            tags = setOf("function", "remote", "catalog"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "calculateTax:2.1.0@tax-catalog" to createSetTask(
                        mapOf(
                            "tax" to $$"${ .amount * .rate }",
                            "total" to $$"${ .amount + (.amount * .rate) }"
                        )
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("tax", 8)
                    put("total", 108)
                }
            )
        ),

        WorkflowTestCase(
            name = "function call from catalog with expression arguments",
            yaml = $$"""
                do:
                  - callCatalog:
                      call: validateEmail:1.0@validation-utils
                      with:
                        email: "${ .user.email }"
            """.trimIndent(),
            input = buildJsonObject {
                put("user", buildJsonObject {
                    put("email", "test@example.com")
                })
            },
            tags = setOf("function", "remote", "catalog"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "validateEmail:1.0@validation-utils" to createSetTask(
                        mapOf(
                            "valid" to true,
                            "normalizedEmail" to $$"${ .email }"
                        )
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("valid", true)
                    put("normalizedEmail", "test@example.com")
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Mixed Function Source Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "workflow can mix named and remote function calls",
            yaml = $$"""
                use:
                  functions:
                    localFunc:
                      set:
                        localValue: "from-local"
                do:
                  - callLocal:
                      call: localFunc
                      export:
                        as: '${ {local: .localValue} }'
                  - callRemote:
                      call: https://example.com/remote.yaml
                      export:
                        as: '${ {remote: .remoteValue} }'
                  - combine:
                      set:
                        combined: "${ $context.local + \"-\" + $context.remote }"
            """.trimIndent(),
            tags = setOf("function", "remote", "mixed"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "https://example.com/remote.yaml" to createSetTask(
                        mapOf("remoteValue" to "from-remote")
                    )
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("combined", "from-local-from-remote")
                }
            )
        ),

        WorkflowTestCase(
            name = "workflow can call functions from multiple catalogs",
            yaml = $$"""
                do:
                  - step1:
                      call: func1:1.0@catalog-a
                      export:
                        as: '${ {a: .value} }'
                  - step2:
                      call: func2:2.0@catalog-b
                      export:
                        as: '${ {b: .value} }'
                  - combine:
                      set:
                        sum: "${ $context.a + $context.b }"
            """.trimIndent(),
            tags = setOf("function", "remote", "catalog", "mixed"),
            mockConfig = MockConfiguration(
                functionDefinitions = mapOf(
                    "func1:1.0@catalog-a" to createSetTask(mapOf("value" to 10)),
                    "func2:2.0@catalog-b" to createSetTask(mapOf("value" to 20))
                )
            ),
            validate = expectOutput(
                buildJsonObject {
                    put("sum", 30)
                }
            )
        ),

        // ─────────────────────────────────────────────────────────────────────
        // Error Handling Tests
        // ─────────────────────────────────────────────────────────────────────

        WorkflowTestCase(
            name = "function error is caught by try/catch",
            yaml = """
                use:
                  functions:
                    failingFunction:
                      raise:
                        error:
                          type: https://example.com/errors/validation
                          status: 400
                          title: "Validation Error"
                          detail: "Function failed validation"
                do:
                  - tryFunc:
                      try:
                        - callFailing:
                            call: failingFunction
                      catch:
                        do:
                          - handleError:
                              set:
                                caught: true
                                errorType: https://example.com/errors/validation
            """.trimIndent(),
            tags = setOf("function", "execution", "error-handling"),
            validate = expectOutput(
                buildJsonObject {
                    put("caught", true)
                    put("errorType", "https://example.com/errors/validation")
                }
            )
        ),

        WorkflowTestCase(
            name = "function error with nested call is caught by outer try/catch",
            yaml = """
                use:
                  functions:
                    innerFailing:
                      raise:
                        error:
                          type: https://example.com/errors/inner
                          status: 500
                          title: "Inner Error"
                    outerFunction:
                      do:
                        - callInner:
                            call: innerFailing
                do:
                  - tryOuter:
                      try:
                        - callOuter:
                            call: outerFunction
                      catch:
                        do:
                          - handleError:
                              set:
                                caughtNested: true
                                source: "outer-try-catch"
            """.trimIndent(),
            tags = setOf("function", "execution", "error-handling", "nested"),
            validate = expectOutput(
                buildJsonObject {
                    put("caughtNested", true)
                    put("source", "outer-try-catch")
                }
            )
        )
    )

    /**
     * Helper function to create a SetTask wrapped in a Task.
     * Used for defining mock function definitions.
     */
    private fun createSetTask(properties: Map<String, Any>): Task {
        return Task().apply {
            setTask = SetTask().apply {
                set = SetTaskConfiguration().apply {
                    properties.forEach { (key, value) ->
                        additionalProperties[key] = value
                    }
                }
            }
        }
    }
}
