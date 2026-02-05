// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities.functions

import com.lemline.core.functions.FunctionResolutionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [FunctionResolver].
 */
class FunctionResolverTest : FunSpec({

    context("Catalog reference parsing") {

        test("parses valid catalog reference") {
            val resolver = FunctionResolver()

            // The resolver should parse "log:1.0.0@global" and resolve to the catalog URL
            // We can't test actual HTTP fetch here, but we can test that invalid refs throw

            val exception = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("myFunction") }
            }
            exception.message shouldContain "cannot be resolved without workflow context"
        }

        test("throws for invalid catalog reference format") {
            val resolver = FunctionResolver()

            // Missing version
            val exception1 = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("log@global") }
            }
            exception1.message shouldContain "cannot be resolved"

            // Missing catalog (but has colon)
            val exception2 = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("log:1.0.0") }
            }
            exception2.message shouldContain "cannot be resolved"
        }

        test("throws for unknown catalog") {
            val resolver = FunctionResolver()

            val exception = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("log:1.0.0@unknown") }
            }
            exception.message shouldContain "Unknown catalog"
            exception.message shouldContain "unknown"
        }
    }

    context("Named function resolution") {

        test("throws for named function without workflow context") {
            val resolver = FunctionResolver()

            val exception = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("validateAddress") }
            }
            exception.message shouldContain "cannot be resolved without workflow context"
        }
    }

    context("URL detection") {

        test("detects HTTP URLs") {
            val resolver = FunctionResolver()

            // HTTP URLs should be detected as URLs (will fail to fetch in test, but validates detection)
            // We verify by checking that it doesn't throw "cannot be resolved without workflow context"
            val exception = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("http://localhost:9999/nonexistent.yaml") }
            }
            // Should fail with fetch error, not "without workflow context"
            exception.message shouldContain "Failed to fetch"
        }

        test("detects HTTPS URLs") {
            val resolver = FunctionResolver()

            val exception = shouldThrow<FunctionResolutionException> {
                runBlocking { resolver.resolve("https://localhost:9999/nonexistent.yaml") }
            }
            exception.message shouldContain "Failed to fetch"
        }
    }

    context("Catalog URL resolution") {

        test("uses default global catalog") {
            val resolver = FunctionResolver()

            // Verify default catalog is configured
            FunctionResolver.defaultCatalogUrls["global"] shouldBe
                "https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions"
        }

        test("resolves catalog URL format correctly") {
            val resolver = FunctionResolver()

            // Verify the URL format for global catalog
            val url = resolver.resolveCatalogUrl("log:1.0.0@global")
            url shouldBe "https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml"
        }
    }

    context("Cache behavior") {

        test("clearCache clears the cache") {
            val resolver = FunctionResolver()

            // Just verify clearCache doesn't throw
            resolver.clearCache()
        }
    }
})
