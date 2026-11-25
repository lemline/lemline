// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

import com.lemline.common.json.*
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
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectErrorContaining
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput

/**
 * Test cases for HTTP call execution.
 * Tests HTTP calls using mock responses from TestMocks.
 */
object CallHttpTestCases {

    private val unavailable = MockConfiguration(httpMocks = listOf(
        HttpMockRule(
            match = HttpMockMatcher(url = "https://api.example.com/inventory"),
            response = HttpMockResponse(status = 503, body = buildJsonObject { put("error", "busy") })
        )
    ))

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
            name = "http call expands a URI template from transformed task input",
            mockConfig = MockConfiguration(httpMocks = listOf(
                HttpMockRule(
                    match = HttpMockMatcher(url = "https://api.example.com/products/SKU-1", method = "GET"),
                    response = HttpMockResponse(body = buildJsonObject { put("id", "SKU-1") })
                )
            )),
            yaml = $$"""
                do:
                  - getProduct:
                      input:
                        from: ${ .request }
                      call: http
                      with:
                        method: get
                        endpoint: https://api.example.com/products/{productId}
            """.trimIndent(),
            input = buildJsonObject {
                put("productId", "SKU-2")
                put("request", buildJsonObject { put("productId", "SKU-1") })
            },
            tags = setOf("http"),
            validate = expectOutput(buildJsonObject { put("id", "SKU-1") })
        ),

        WorkflowTestCase(
            name = "http call percent encodes scalar URI template values in an endpoint object",
            mockConfig = MockConfiguration(httpMocks = listOf(
                HttpMockRule(
                    match = HttpMockMatcher(
                        url = "https://api.example.com/a%20b%2B%2F%C3%A9%252F%2A~%7B%7D%3F/42/true/%7Bliteral%7D/%2F/end?missing=&empty="
                    ),
                    response = HttpMockResponse(body = buildJsonObject { put("matched", true) })
                )
            )),
            yaml = """
                do:
                  - getPet:
                      call: http
                      with:
                        method: get
                        endpoint:
                          uri: https://api.example.com/{pet.id}/{count}/{enabled}/%7Bliteral%7D/%2F/end?missing={missing}&empty={empty}
            """.trimIndent(),
            input = parseJson("""
                {"pet.id":"a b+/é%2F*~{}?","pet":{"id":"nested id"},"count":42,"enabled":true,"literal":"ignored","empty":null}
            """),
            tags = setOf("http"),
            validate = expectOutput(buildJsonObject { put("matched", true) })
        ),

        WorkflowTestCase(
            name = "http call leaves URI placeholders from a jq endpoint expression unchanged",
            mockConfig = MockConfiguration(httpMocks = listOf(
                HttpMockRule(
                    match = HttpMockMatcher(url = """https://api.example.com/\{productId\}"""),
                    response = HttpMockResponse(body = buildJsonObject { put("matched", true) })
                )
            )),
            yaml = $$"""
                do:
                  - callComputedUrl:
                      call: http
                      with:
                        method: get
                        endpoint: '${ "https://api.example.com/" + .path }'
            """.trimIndent(),
            input = buildJsonObject {
                put("path", "{productId}")
                put("productId", "SKU-1")
            },
            tags = setOf("http"),
            validate = expectOutput(buildJsonObject { put("matched", true) })
        ),

        WorkflowTestCase(
            name = "http call raises an expression error for a non-scalar URI template value",
            mockConfig = MockConfiguration(),
            yaml = """
                do:
                  - request:
                      try:
                        - getPet:
                            call: http
                            with:
                              method: get
                              endpoint: https://api.example.com/{pet}
                      catch:
                        errors:
                          with:
                            type: https://open-workflow-specification.org/spec/1.0.0/errors/expression
                            status: 400
                        do:
                          - report:
                              set:
                                caught: true
            """.trimIndent(),
            input = buildJsonObject { put("pet", buildJsonObject { put("id", "nested id") }) },
            tags = setOf("http"),
            validate = expectOutput(buildJsonObject { put("caught", true) })
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
                          postId: '1'
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
        ),

        WorkflowTestCase(
            name = "http call failing with a non-2xx status raises a communication error carrying that status",
            mockConfig = unavailable,
            yaml = $$"""
                do:
                  - fetchInventory:
                      try:
                        - requestInventory:
                            call: http
                            with:
                              method: get
                              endpoint: https://api.example.com/inventory
                      catch:
                        errors:
                          with:
                            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
                            status: 503
                        as: failure
                        do:
                          - report:
                              set:
                                type: ${ $failure.type }
                                status: ${ $failure.status }
                                title: ${ $failure.title }
            """.trimIndent(),
            tags = setOf("http"),
            validate = expectOutput(
                buildJsonObject {
                    put("type", "https://open-workflow-specification.org/spec/1.0.0/errors/communication")
                    put("status", 503)
                    put("title", """HTTP 503: {"error":"busy"}""")
                }
            )
        ),

        WorkflowTestCase(
            name = "http call failing with another status is not caught by a status filter",
            mockConfig = unavailable,
            yaml = """
                do:
                  - fetchInventory:
                      try:
                        - requestInventory:
                            call: http
                            with:
                              method: get
                              endpoint: https://api.example.com/inventory
                      catch:
                        errors:
                          with:
                            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
                            status: 404
                        do:
                          - report:
                              set:
                                caught: true
            """.trimIndent(),
            tags = setOf("http"),
            validate = expectErrorContaining("errors/communication")
        )
    )
}
