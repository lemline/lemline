// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test cases for export.as directive.
 * Tests exporting data to workflow context ($context).
 */
object ExportContextTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "export context using expression syntax",
            yaml = $$"""
                do:
                  - first:
                      set:
                        foo: 42
                      export:
                        as: "${ {ctx: .} }"
                  - second:
                      set:
                        number: ${ $context.ctx.foo }
            """.trimIndent(),
            validate = expectOutputMatching("number=42") { output ->
                output == buildJsonObject {
                    put("number", 42)
                }
            }
        ),

        WorkflowTestCase(
            name = "export context using JSON object",
            yaml = $$"""
                do:
                  - first:
                      set:
                        foo: 42
                      export:
                        as:
                          ctx: .
                  - second:
                      set:
                        number: ${ $context.ctx.foo }
            """.trimIndent(),
            validate = expectOutputMatching("number=42") { output ->
                output == buildJsonObject {
                    put("number", 42)
                }
            }
        ),

        WorkflowTestCase(
            name = "export partial data to context",
            yaml = $$"""
                do:
                  - first:
                      set:
                        foo: 42
                        bar: "hello"
                      export:
                        as:
                          onlyFoo: .foo
                  - second:
                      set:
                        fromContext: ${ $context.onlyFoo }
            """.trimIndent(),
            validate = expectOutputMatching("fromContext=42") { output ->
                output == buildJsonObject {
                    put("fromContext", 42)
                }
            }
        ),

        WorkflowTestCase(
            name = "multiple tasks can export to context",
            yaml = $$"""
                do:
                  - first:
                      set:
                        value1: 10
                      export:
                        as:
                          first: .value1
                  - second:
                      set:
                        value2: 20
                      export:
                        as:
                          first: ${ $context.first }
                          second: .value2
                  - third:
                      set:
                        sum: ${ $context.first + $context.second }
            """.trimIndent(),
            validate = expectOutputMatching("sum=30") { output ->
                output == buildJsonObject {
                    put("sum", 30)
                }
            }
        ),

        WorkflowTestCase(
            name = "export can overwrite previous context values",
            yaml = $$"""
                do:
                  - first:
                      set:
                        value: 10
                      export:
                        as:
                          shared: .value
                  - second:
                      set:
                        value: 20
                      export:
                        as:
                          shared: .value
                  - third:
                      set:
                        result: ${ $context.shared }
            """.trimIndent(),
            validate = expectOutputMatching("result=20") { output ->
                output == buildJsonObject {
                    put("result", 20)
                }
            }
        ),

        WorkflowTestCase(
            name = "export works with nested tasks",
            yaml = $$"""
                do:
                  - outer:
                      do:
                        - inner:
                            set:
                              nested: "value"
                            export:
                              as:
                                fromNested: .nested
                  - useNested:
                      set:
                        result: ${ $context.fromNested }
            """.trimIndent(),
            validate = expectOutputMatching("result=value") { output ->
                output == buildJsonObject {
                    put("result", "value")
                }
            }
        ),

        WorkflowTestCase(
            name = "export can transform data before exporting",
            yaml = $$"""
                do:
                  - compute:
                      set:
                        x: 10
                        y: 20
                      export:
                        as:
                          sum: ${ .x + .y }
                  - use:
                      set:
                        doubled: ${ $context.sum * 2 }
            """.trimIndent(),
            validate = expectOutputMatching("doubled=60") { output ->
                output == buildJsonObject {
                    put("doubled", 60)
                }
            }
        )
    )
}
