// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.MockConfigurationParser
import com.lemline.core.activities.mock.ScriptMockMatcher
import com.lemline.core.activities.mock.ScriptMockResponse
import com.lemline.core.activities.mock.ScriptMockRule
import com.lemline.core.activities.mock.ShellMockMatcher
import com.lemline.core.activities.mock.ShellMockResponse
import com.lemline.core.activities.mock.ShellMockRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for [MockConfigurationParser].
 */
class MockConfigurationParserTest : FunSpec({

    test("parse empty YAML") {
        val yaml = ""
        val config = MockConfigurationParser.fromYaml(yaml)

        config.httpMocks shouldHaveSize 0
        config.scriptMocks shouldHaveSize 0
        config.shellMocks shouldHaveSize 0
    }

    test("parse HTTP mock with URL and method") {
        val yaml = """
            http:
              - match:
                  url: "*api.example.com*"
                  method: GET
                response:
                  status: 200
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.httpMocks shouldHaveSize 1
        val mock = config.httpMocks[0]
        mock.match.url shouldBe "*api.example.com*"
        mock.match.method shouldBe "GET"
        mock.response.status shouldBe 200
    }

    test("parse HTTP mock with error response") {
        val yaml = """
            http:
              - match:
                  url: "*failing.api*"
                response:
                  status: 500
                  error: "Service unavailable"
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.httpMocks shouldHaveSize 1
        val mock = config.httpMocks[0]
        mock.match.url shouldBe "*failing.api*"
        mock.response.status shouldBe 500
        mock.response.error shouldBe "Service unavailable"
    }

    test("parse script mock") {
        val yaml = """
            script:
              - match:
                  language: javascript
                response:
                  exitCode: 0
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.scriptMocks shouldHaveSize 1
        val mock = config.scriptMocks[0]
        mock.match.language shouldBe "javascript"
        mock.response.exitCode shouldBe 0
    }

    test("parse script mock with error exit code") {
        val yaml = """
            script:
              - match:
                  language: python
                response:
                  exitCode: 1
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.scriptMocks shouldHaveSize 1
        val mock = config.scriptMocks[0]
        mock.match.language shouldBe "python"
        mock.response.exitCode shouldBe 1
    }

    test("parse shell mock") {
        val yaml = """
            shell:
              - match:
                  command: "echo*"
                response:
                  stdout: "mocked output"
                  exitCode: 0
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.shellMocks shouldHaveSize 1
        val mock = config.shellMocks[0]
        mock.match.command shouldBe "echo*"
        mock.response.stdout shouldBe "mocked output"
        mock.response.exitCode shouldBe 0
    }

    test("parse shell mock with stderr") {
        val yaml = """
            shell:
              - match:
                  command: "failing-cmd"
                response:
                  stdout: ""
                  stderr: "Error message"
                  exitCode: 1
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.shellMocks shouldHaveSize 1
        val mock = config.shellMocks[0]
        mock.response.stderr shouldBe "Error message"
        mock.response.exitCode shouldBe 1
    }

    test("parse mixed configuration") {
        val yaml = """
            http:
              - match:
                  url: "*api.example.com*"
                response:
                  status: 200
            script:
              - match:
                  language: python
                response:
                  exitCode: 0
            shell:
              - match:
                  command: "ls*"
                response:
                  stdout: "file1.txt\nfile2.txt"
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.httpMocks shouldHaveSize 1
        config.scriptMocks shouldHaveSize 1
        config.shellMocks shouldHaveSize 1
    }

    test("parse multiple HTTP mocks") {
        val yaml = """
            http:
              - match:
                  url: "*api.example.com/users*"
                  method: GET
                response:
                  status: 200
              - match:
                  url: "*api.example.com/users*"
                  method: POST
                response:
                  status: 201
        """.trimIndent()

        val config = MockConfigurationParser.fromYaml(yaml)

        config.httpMocks shouldHaveSize 2
        config.httpMocks[0].match.method shouldBe "GET"
        config.httpMocks[1].match.method shouldBe "POST"
    }

    test("parse JSON format") {
        val json = """
            {
                "http": [
                    {
                        "match": { "url": "*api.example.com*" },
                        "response": { "status": 200, "body": { "ok": true } }
                    }
                ]
            }
        """.trimIndent()

        val config = MockConfigurationParser.fromJson(json)

        config.httpMocks shouldHaveSize 1
        config.httpMocks[0].response.status shouldBe 200
    }

    context("findHttpMock") {
        test("find by URL pattern") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*example.com*"),
                        HttpMockResponse(status = 200)
                    )
                )
            )

            val mock = config.findHttpMock("https://api.example.com/users", "GET")
            mock.shouldNotBeNull()
            mock.response.status shouldBe 200
        }

        test("find by method") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(method = "POST"),
                        HttpMockResponse(status = 201)
                    )
                )
            )

            val mock = config.findHttpMock("https://any-url.com", "POST")
            mock.shouldNotBeNull()
            mock.response.status shouldBe 201
        }

        test("returns first matching mock") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*"),
                        HttpMockResponse(status = 200)
                    ),
                    HttpMockRule(
                        HttpMockMatcher(url = "*specific*"),
                        HttpMockResponse(status = 201)
                    )
                )
            )

            val mock = config.findHttpMock("https://specific.com", "GET")
            mock.shouldNotBeNull()
            mock.response.status shouldBe 200  // First match wins
        }

        test("returns null when no match") {
            val config = MockConfiguration(
                httpMocks = listOf(
                    HttpMockRule(
                        HttpMockMatcher(url = "*specific.com*", method = "POST"),
                        HttpMockResponse(status = 200)
                    )
                )
            )

            val mock = config.findHttpMock("https://other.com", "GET")
            mock shouldBe null
        }
    }

    context("findScriptMock") {
        test("find by language") {
            val config = MockConfiguration(
                scriptMocks = listOf(
                    ScriptMockRule(
                        ScriptMockMatcher(language = "javascript"),
                        ScriptMockResponse(exitCode = 0)
                    )
                )
            )

            val mock = config.findScriptMock("JavaScript")  // case insensitive
            mock.shouldNotBeNull()
        }
    }

    context("findShellMock") {
        test("find by command pattern") {
            val config = MockConfiguration(
                shellMocks = listOf(
                    ShellMockRule(
                        ShellMockMatcher(command = "echo*"),
                        ShellMockResponse(stdout = "mocked")
                    )
                )
            )

            val mock = config.findShellMock("echo hello world")
            mock.shouldNotBeNull()
            mock.response.stdout shouldBe "mocked"
        }
    }
})
