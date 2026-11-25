// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities.functions

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.functions.FunctionResolutionException
import com.lemline.core.functions.FunctionResolver as CoreFunctionResolver
import com.lemline.runner.common.activities.TestModeConfiguration
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.serverlessworkflow.api.types.Catalog
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves remote function references to Task definitions.
 *
 * Implements [CoreFunctionResolver] to resolve:
 * - **URL**: Direct function definition URL (e.g., `https://example.com/function.yaml`)
 * - **Catalog**: Catalog reference in format `name:version@catalog` (e.g., `log:1.0.0@global`)
 *
 * Named functions (from workflow's `use.functions`) are resolved directly by the caller
 * since they're a simple map lookup.
 *
 * ## Test Mode Support
 *
 * In test mode (when [TestModeConfiguration.mockConfiguration] is set), function definitions
 * are first looked up from the mock configuration before falling back to the source URI.
 *
 * ## Caching
 *
 * Task definitions are cached in memory to avoid repeatedly fetching the same file.
 * The cache is keyed by the resolved URL.
 *
 * ## Catalog Resolution
 *
 * Catalog references follow the format: `{name}:{version}@{catalog}`
 * - `name`: Function name (letters, digits and hyphens)
 * - `version`: Semantic version (e.g., `1.0.0`)
 * - `catalog`: Catalog name, resolved from the workflow's `use.catalogs` or the built-in catalogs
 *
 * Default catalog URLs:
 * - `global` → `https://raw.githubusercontent.com/open-workflow-specification/catalog/main/functions`
 */
@ApplicationScoped
class FunctionResolver : CoreFunctionResolver {

    @Inject
    var testModeConfiguration: TestModeConfiguration = TestModeConfiguration()

    private val logger = logger()

    /** In-memory cache of resolved Task definitions */
    private val cache = ConcurrentHashMap<String, Task>()

    private val httpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
            }
        }
    }

    /**
     * Resolves a remote function reference to its Task definition.
     *
     * In test mode, first checks the mock configuration for a function definition.
     *
     * @param functionRef The function reference (URL or catalog ref)
     * @return The resolved [Task] definition
     * @throws FunctionResolutionException if the function cannot be resolved
     */
    override suspend fun resolve(functionRef: String): Task = resolve(functionRef, emptyMap())

    override fun forWorkflow(workflow: Workflow): suspend (String) -> Task {
        val catalogs = workflow.use?.catalogs?.additionalProperties.orEmpty()
        return { resolve(it, catalogs) }
    }

    private suspend fun resolve(functionRef: String, catalogs: Map<String, Catalog>): Task {
        logger.debug { "Resolving function reference: $functionRef" }

        // In test mode, check mock configuration first
        testModeConfiguration.mockConfiguration?.let { mockConfig ->
            mockConfig.getFunctionDefinition(functionRef)?.let {
                logger.debug { "Using mock function definition for: $functionRef" }
                return it
            }
        }

        return when {
            // Direct URL
            functionRef.startsWith("http://") || functionRef.startsWith("https://") -> {
                fetchFromUrl(functionRef)
            }
            // Catalog reference: name:version@catalog
            functionRef.contains("@") && functionRef.contains(":") -> {
                val catalogUrl = resolveCatalogUrl(functionRef, catalogs)
                fetchFromUrl(catalogUrl)
            }
            // Named function - cannot be resolved here without workflow context
            else -> {
                throw FunctionResolutionException(
                    "Named function '$functionRef' cannot be resolved without workflow context. " +
                        "Named functions must be defined in the workflow's use.functions section."
                )
            }
        }
    }

    /**
     * Fetches a Task definition from a URL.
     *
     * Reads function definitions from HTTP(S) and file URLs.
     *
     * @param url The URL to fetch from
     * @return The parsed Task definition
     */
    private suspend fun fetchFromUrl(url: String): Task {
        // Check cache first
        cache[url]?.let {
            logger.debug { "Function definition found in cache: $url" }
            return it
        }

        logger.info { "Fetching function definition from: $url" }

        val content = try {
            if (url.startsWith("file:", ignoreCase = true)) {
                withContext(Dispatchers.IO) { Files.readString(Path.of(URI.create(url))) }
            } else {
                val response = httpClient.get(url)
                if (!response.status.isSuccess()) {
                    throw FunctionResolutionException("Failed to fetch function from $url: HTTP ${response.status.value}")
                }
                response.body<String>()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FunctionResolutionException) {
            throw e
        } catch (e: Exception) {
            throw FunctionResolutionException("Failed to fetch function from $url: ${e.message}", e)
        }
        val task = parseTask(content, url)

        // Cache the result
        cache[url] = task
        logger.debug { "Function definition cached: $url" }

        return task
    }

    /**
     * Parses a Task from function definition YAML/JSON content.
     *
     * Function definitions are essentially task definitions that can be
     * directly deserialized to a Task object.
     *
     * @param content The raw content (YAML or JSON)
     * @param source Source URL for error messages
     * @return The parsed Task
     */
    private fun parseTask(content: String, source: String): Task {
        return try {
            // Parse YAML to JSON tree, then convert to Task
            val jsonNode = LemlineJson.yamlMapper.readTree(content)
            LemlineJson.jacksonMapper.treeToValue(jsonNode, Task::class.java)
        } catch (e: Exception) {
            throw FunctionResolutionException(
                "Failed to parse function definition from $source: ${e.message}", e
            )
        }
    }

    /**
     * Resolves a catalog reference to a URL.
     *
     * Format: `name:version@catalog`
     * Example: `log:1.0.0@global` → `https://.../functions/log/1.0.0/function.yaml`
     *
     * @param catalogRef The catalog reference
     * @return The resolved URL
     */
    internal fun resolveCatalogUrl(catalogRef: String, catalogs: Map<String, Catalog> = emptyMap()): String {
        val match = CATALOG_PATTERN.matchEntire(catalogRef)
            ?: throw FunctionResolutionException(
                "Invalid catalog reference format: $catalogRef. Expected: name:version@catalog " +
                    "(function and catalog names may contain letters, digits and hyphens)"
            )

        val (name, version, catalog) = match.destructured

        val baseUrl = catalogs[catalog]?.let { catalogEndpoint(catalog, it) } ?: defaultCatalogUrls[catalog]
            ?: throw FunctionResolutionException(
                "Unknown catalog: '$catalog'. Available catalogs: ${defaultCatalogUrls.keys + catalogs.keys}"
            )

        return "${baseUrl.trimEnd('/')}/$name/$version/function.yaml"
    }

    private fun catalogEndpoint(name: String, catalog: Catalog): String {
        val endpoint = catalog.endpoint?.get()
        val uriTemplate = when (endpoint) {
            is EndpointConfiguration -> {
                if (endpoint.authentication != null) {
                    throw FunctionResolutionException("Catalog '$name' endpoint authentication is not supported")
                }
                endpoint.uri?.get() as? UriTemplate
            }
            else -> endpoint as? UriTemplate
        }
        val uri = uriTemplate?.literalUri
            ?: throw FunctionResolutionException("Catalog '$name' requires a literal endpoint URI")
        if (uri.scheme?.lowercase() !in CATALOG_SCHEMES || uri.isOpaque || uri.rawQuery != null || uri.rawFragment != null) {
            throw FunctionResolutionException("Catalog '$name' requires an HTTP, HTTPS or file endpoint without a query or fragment")
        }
        return uri.toString()
    }

    /**
     * Clears the function definition cache.
     */
    fun clearCache() {
        cache.clear()
        logger.debug { "Function definition cache cleared" }
    }

    companion object {
        /** Pattern for catalog references: name:version@catalog */
        private val CATALOG_PATTERN = Regex("""([A-Za-z0-9-]+):([A-Za-z0-9][A-Za-z0-9.+-]*)@([A-Za-z0-9-]+)""")
        private val CATALOG_SCHEMES = setOf("http", "https", "file")

        /** Default catalog URL mappings */
        val defaultCatalogUrls = mapOf(
            "global" to "https://raw.githubusercontent.com/open-workflow-specification/catalog/main/functions"
        )
    }
}
