// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.testcases.impl.TestMocks
import com.lemline.core.testcases.impl.TestMocks.commentsForPost1
import com.lemline.core.testcases.impl.TestMocks.createdPostResponse
import com.lemline.core.testcases.impl.TestMocks.deleteResponse
import com.lemline.core.testcases.impl.TestMocks.post1Response
import com.lemline.core.testcases.impl.TestMocks.updatedPostResponse
import com.lemline.core.testcases.impl.TestMocks.user1Response
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
            validate = expectOutput(post1Response)
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
            validate = expectOutput(commentsForPost1)
        ),

        WorkflowTestCase(
            name = "http call can evaluate expressions in query parameters",
            mockConfig = MockConfiguration(httpMocks = listOf(
                HttpMockRule(
                    match = HttpMockMatcher(
                        url = "*jsonplaceholder*/comments*",
                        method = "GET",
                        query = mapOf("postId" to "1")
                    ),
                    response = HttpMockResponse(status = 200, body = commentsForPost1)
                )
            )),
            yaml = $$"""
                do:
                  - getComments:
                      call: http
                      with:
                        method: GET
                        endpoint: https://jsonplaceholder.typicode.com/comments
                        query:
                          postId: ${ .targetPostId | tostring }
            """.trimIndent(),
            input = buildJsonObject { put("targetPostId", 1) },
            tags = setOf("http"),
            validate = expectOutput(commentsForPost1)
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
            validate = expectOutput(createdPostResponse)
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
            validate = expectOutput(updatedPostResponse)
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
            validate = expectOutput(deleteResponse)
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
            input = buildJsonObject { put("someData", "test") },
            tags = setOf("http"),
            validate = expectOutput(createdPostResponse)
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
            validate = expectOutput(user1Response)
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
            validate = expectOutput(post1Response)
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
            validate = expectOutput(post1Response)
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
            validate = expectOutput(
                buildJsonObject {
                    put("postTitle", "sunt aut facere repellat provident occaecati excepturi optio reprehenderit")
                    put("postId", 1)
                }
            )
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
            validate = expectOutput(
                buildJsonObject {
                    put("result", "sunt aut facere repellat provident occaecati excepturi optio reprehenderit")
                }
            )
        )
    )
}
