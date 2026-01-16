// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.TestMocks
import com.lemline.core.testcases.impl.TestMocks.calculateTotalResponse
import com.lemline.core.testcases.impl.TestMocks.logFunctionResponse
import com.lemline.core.testcases.impl.TestMocks.validateAddressResponse
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test cases for function call execution.
 * Tests function calls using mock responses from TestMocks.
 */
object CallFunctionTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "function call can invoke named function",
            mockConfig = TestMocks.functionConfig,
            yaml = """
                do:
                  - validate:
                      call: validateAddress
                      with:
                        street: 123 Main St
                        city: Springfield
                        zipCode: 12345
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(validateAddressResponse)
        ),

        WorkflowTestCase(
            name = "function call can invoke function from URL",
            mockConfig = TestMocks.functionConfig,
            yaml = """
                do:
                  - log:
                      call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
                      with:
                        message: Hello, world!
                        level: information
            """.trimIndent(),
            tags = setOf("function", "external"),
            validate = expectOutput(logFunctionResponse)
        ),

        WorkflowTestCase(
            name = "function call can invoke function without arguments",
            mockConfig = TestMocks.functionConfig,
            yaml = """
                do:
                  - calculate:
                      call: calculateTotal
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(calculateTotalResponse)
        ),

        WorkflowTestCase(
            name = "function call can use expressions in arguments",
            mockConfig = TestMocks.functionConfig,
            yaml = $$"""
                do:
                  - validate:
                      call: validateAddress
                      with:
                        street: "${ .customer.street }"
                        city: "${ .customer.city }"
                        zipCode: "${ .customer.zip }"
            """.trimIndent(),
            input = buildJsonObject {
                put("customer", buildJsonObject {
                    put("street", "123 Main St")
                    put("city", "Springfield")
                    put("zip", "12345")
                })
            },
            tags = setOf("function"),
            validate = expectOutput(validateAddressResponse)
        ),

        WorkflowTestCase(
            name = "function call result can be transformed with output as",
            mockConfig = TestMocks.functionConfig,
            yaml = $$"""
                do:
                  - validate:
                      call: validateAddress
                      with:
                        street: 123 Main St
                        city: Springfield
                        zipCode: 12345
                      output:
                        as: '${ {isValid: .valid, address: .normalized} }'
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(
                buildJsonObject {
                    put("isValid", true)
                    put("address", buildJsonObject {
                        put("street", "123 Main St")
                        put("city", "Springfield")
                        put("zipCode", "12345")
                    })
                }
            )
        ),

        WorkflowTestCase(
            name = "function call can chain multiple function calls",
            mockConfig = TestMocks.functionConfig,
            yaml = """
                do:
                  - validate:
                      call: validateAddress
                      with:
                        street: 123 Main St
                        city: Springfield
                        zipCode: 12345
                  - calculate:
                      call: calculateTotal
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(calculateTotalResponse)
        ),

        WorkflowTestCase(
            name = "function call can be used within workflow steps",
            mockConfig = TestMocks.functionConfig,
            yaml = $$"""
                do:
                  - step1:
                      call: validateAddress
                      with:
                        street: 123 Main St
                        city: Springfield
                        zipCode: 12345
                  - step2:
                      set:
                        validationResult: ${ .valid }
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(
                buildJsonObject {
                    put("validationResult", true)
                }
            )
        ),

        WorkflowTestCase(
            name = "function call can export results to context",
            mockConfig = TestMocks.functionConfig,
            yaml = $$"""
                do:
                  - validate:
                      call: validateAddress
                      with:
                        street: 123 Main St
                        city: Springfield
                        zipCode: 12345
                      export:
                        as: '${ {validationStatus: .valid} }'
                  - result:
                      set:
                        status: ${ $context.validationStatus }
            """.trimIndent(),
            tags = setOf("function"),
            validate = expectOutput(
                buildJsonObject {
                    put("status", true)
                }
            )
        )
    )
}
