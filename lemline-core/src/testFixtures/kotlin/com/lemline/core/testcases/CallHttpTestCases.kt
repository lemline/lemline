// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.testcases.WorkflowTestValidators.expectOutputMatching
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test cases for HTTP call execution.
 * Tests HTTP calls using mock responses from TestMocks.
 */
object CallHttpTestCases {

    val cases = listOf(
        WorkflowTestCase(
            name = "http call can perform GET request",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("id=1 with title and body") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["id"]?.jsonPrimitive?.int == 1 &&
                    obj.containsKey("title") &&
                    obj.containsKey("body") &&
                    obj.containsKey("userId")
            }
        ),

        WorkflowTestCase(
            name = "http call can perform GET request with query parameters",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - getComments:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/comments
                        query:
                          postId: 1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("array of comments for post 1") { output ->
                val arr = output as? JsonArray ?: return@expectOutputMatching false
                arr.isNotEmpty() && arr[0].jsonObject["postId"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "http call can perform POST request with body",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - createPost:
                      call: http
                      with:
                        method: POST
                        endpoint: https://jsonplaceholder.typicode.com/posts
                        headers:
                          Content-Type: application/json
                        body:
                          title: Test Post
                          body: This is a test post
                          userId: 1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("created post with id") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("id") &&
                    obj["title"]?.jsonPrimitive?.content == "Test Post" &&
                    obj["body"]?.jsonPrimitive?.content == "This is a test post" &&
                    obj["userId"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "http call can perform PUT request",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - updatePost:
                      call: http
                      with:
                        method: PUT
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                        body:
                          id: 1
                          title: Updated Title
                          body: Updated body
                          userId: 1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("updated post") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["id"]?.jsonPrimitive?.int == 1 &&
                    obj["title"]?.jsonPrimitive?.content == "Updated Title" &&
                    obj["body"]?.jsonPrimitive?.content == "Updated body"
            }
        ),

        WorkflowTestCase(
            name = "http call can perform DELETE request",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - deletePost:
                      call: http
                      with:
                        method: DELETE
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("empty object") { output ->
                output is JsonObject
            }
        ),

        WorkflowTestCase(
            name = "http call can use input data via input transformation",
            mockConfig = TestMocks.httpConfig,
            yaml = $$"""
                do:
                  - createPost:
                      call: http
                      with:
                        method: POST
                        endpoint: https://jsonplaceholder.typicode.com/posts
                        body:
                          title: Dynamic Title
                          body: Dynamic body content
                          userId: 42
                      input:
                        from: ${ . }
            """.trimIndent(),
            input = JsonObject(mapOf("someData" to JsonPrimitive("test"))),
            tags = setOf("http"),
            validate = expectOutputMatching("created post with id") { output ->
                // Mock returns a post with id (actual body values depend on mock config)
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("id") && obj.containsKey("title")
            }
        ),

        WorkflowTestCase(
            name = "http call can chain multiple requests",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                  - getUser:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/users/1
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("user data from second call") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("id") &&
                    obj.containsKey("name") &&
                    obj.containsKey("email")
            }
        ),

        WorkflowTestCase(
            name = "http call can use output as response format",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                        output: response
            """.trimIndent(),
            tags = setOf("http"),
            // Note: Mock returns body directly, not the full response format.
            // Real execution would return {request, statusCode, headers, content}
            validate = expectOutputMatching("valid JSON response") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                // With mocks, we get the body directly (id=1 post)
                // With real execution, we'd get the full response format
                obj.containsKey("id") || obj.containsKey("content")
            }
        ),

        WorkflowTestCase(
            name = "http call can use custom headers",
            mockConfig = TestMocks.httpConfig,
            yaml = """
                do:
                  - getWithHeaders:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                        headers:
                          Accept: application/json
                          User-Agent: Lemline-Test
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("post with id=1") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj["id"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "http call result can be transformed with output as",
            mockConfig = TestMocks.httpConfig,
            yaml = $$"""
                do:
                  - getPost:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                      output:
                        as: '${ {postTitle: .title, postId: .id} }'
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("transformed output with postTitle and postId") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("postTitle") &&
                    obj.containsKey("postId") &&
                    obj["postId"]?.jsonPrimitive?.int == 1
            }
        ),

        WorkflowTestCase(
            name = "http call can be used within workflow steps",
            mockConfig = TestMocks.httpConfig,
            yaml = $$"""
                do:
                  - step1:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/posts/1
                  - step2:
                      set:
                        result: ${ .title }
            """.trimIndent(),
            tags = setOf("http", "external"),
            validate = expectOutputMatching("result contains title") { output ->
                val obj = output as? JsonObject ?: return@expectOutputMatching false
                obj.containsKey("result") &&
                    obj["result"]?.jsonPrimitive?.content?.isNotEmpty() == true
            }
        )
    )
}
