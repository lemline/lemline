// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectErrorContaining
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Test cases for TryTask execution.
 * Tests error handling with try/catch blocks, retry logic, and error filtering.
 */
object TryTaskTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "try catch - basic error is caught",
            yaml = """
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
            """.trimIndent(),
            validate = expectOutputMatching("caught=true, errorHandled=yes") { output ->
                output == buildJsonObject {
                    put("caught", true)
                    put("errorHandled", "yes")
                }
            }
        ),

        WorkflowTestCase(
            name = "try catch - unhandled error propagates when no matching catch",
            yaml = """
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
            """.trimIndent(),
            validate = expectErrorContaining("runtime")
        ),

        WorkflowTestCase(
            name = "try catch - error type filtering matches",
            yaml = """
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
            """.trimIndent(),
            validate = expectOutputMatching("caught=true") { output ->
                output == buildJsonObject { put("caught", true) }
            }
        ),

        WorkflowTestCase(
            name = "try catch - error type filtering does not match",
            yaml = """
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
            """.trimIndent(),
            validate = expectErrorContaining("runtime")
        ),

        WorkflowTestCase(
            name = "try catch - error status filtering matches",
            yaml = """
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
            """.trimIndent(),
            validate = expectOutputMatching("caught=true") { output ->
                output == buildJsonObject { put("caught", true) }
            }
        ),

        WorkflowTestCase(
            name = "try catch - error data is accessible in catch block",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("errorType and errorStatus set") { output ->
                output == buildJsonObject {
                    put("errorType", "https://serverlessworkflow.io/spec/1.0.0/errors/runtime")
                    put("errorStatus", 500)
                }
            }
        ),

        WorkflowTestCase(
            name = "try catch - custom error variable name",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("issueType and issueStatus set") { output ->
                output == buildJsonObject {
                    put("issueType", "https://serverlessworkflow.io/spec/1.0.0/errors/runtime")
                    put("issueStatus", 500)
                }
            }
        ),

        WorkflowTestCase(
            name = "try catch - when condition matches",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectOutputMatching("caught=true") { output ->
                output == buildJsonObject { put("caught", true) }
            }
        ),

        WorkflowTestCase(
            name = "try catch - when condition does not match",
            yaml = $$"""
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
            """.trimIndent(),
            validate = expectErrorContaining("runtime")
        ),

        WorkflowTestCase(
            name = "try catch - successful try block does not enter catch",
            yaml = $$"""
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
            """.trimIndent(),
            // Uses containsKey to verify field absence - keep property-based validation
            validate = expectOutputMatching("final=true, no caught") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["final"]?.jsonPrimitive?.content == "true" &&
                    !obj.containsKey("caught")
            }
        ),

        WorkflowTestCase(
            name = "try catch - error in nested task is caught by outer try",
            yaml = """
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
            """.trimIndent(),
            validate = expectOutputMatching("caught=true") { output ->
                output == buildJsonObject { put("caught", true) }
            }
        ),

        WorkflowTestCase(
            name = "try catch - original input is preserved in catch block",
            yaml = $$"""
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
                                receivedValue: ${ .initialValue }
            """.trimIndent(),
            validate = expectOutputMatching("receivedValue=42") { output ->
                output == buildJsonObject { put("receivedValue", 42) }
            }
        )
    )
}
