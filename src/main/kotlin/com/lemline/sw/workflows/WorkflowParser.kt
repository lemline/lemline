package com.lemline.sw.workflows

import com.lemline.sw.nodes.JsonPointer
import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodePosition
import com.lemline.sw.nodes.RootTask
import com.lemline.worker.repositories.WorkflowDefinitionRepository
import com.lemline.worker.system.System
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

typealias WorkflowIndex = Pair<String, String>

@ApplicationScoped
class WorkflowParser(
    private val workflowDefinitionRepository: WorkflowDefinitionRepository,
) {
    /**
     * Retrieves a workflow definition by its name and version.
     *
     * This method first checks the cache for the workflow definition. If not found,
     * it loads the workflow definition from the database, validates it, caches it, and returns it.
     *
     * @param name The name of the workflow.
     * @param version The version of the workflow.
     * @return The workflow definition.
     * @throws IllegalArgumentException if the workflow definition is not found in the database.
     */
    fun getWorkflow(name: String, version: String): Workflow =
        workflowCache.getOrPut(name to version) {
            // Load workflow definition from database
            val workflowDefinition = workflowDefinitionRepository.findByNameAndVersion(name, version)
                ?: error("Workflow $name:$version not found")
            // load and validate workflow definition
            parseWorkflow(workflowDefinition.definition)
        }

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return A map of secret names to their JsonNode values from environment variables
     * @throws IllegalStateException if a required secret is not found in environment variables
     */
    fun getSecrets(workflow: Workflow): Map<String, JsonElement> =
        secretsCache.getOrPut(workflow.index) {
            workflow.use?.secrets?.associateWith { secretName ->
                val value = System.getEnv(secretName)
                    ?: error("Required secret '$secretName' not found in environment variables")
                try {
                    Json.decodeFromString<JsonObject>(value)
                } catch (e: Exception) {
                    JsonPrimitive(value)
                }
            } ?: emptyMap()
        }

    /**
     * Retrieves the root node of the given workflow.
     * The root node is the Node<DoTask> at the root level of the workflow.
     *
     * @param workflow The workflow containing the root node.
     * @return The root node of the workflow.
     */
    @Suppress("UNCHECKED_CAST")
    fun getRootNode(workflow: Workflow): Node<RootTask> =
        getNode(workflow, NodePosition.root) as Node<RootTask>

    /**
     * Retrieves the task node at the specified initialPosition in the workflow.
     *
     * @param workflow The workflow containing the task node.
     * @param position The initialPosition of the task node to retrieve.
     * @return The task node at the specified initialPosition.
     * @throws IllegalStateException if the task node is not found at the specified initialPosition.
     */
    private fun getNode(workflow: Workflow, position: NodePosition): Node<*> =
        nodesCache[workflow.index]?.get(position.jsonPointer)
            ?: error("Task node not found at initialPosition $position for workflow ${workflow.document.name} (version ${workflow.document.version})")

    /**
     * Parses the given workflow definition string and returns a Workflow object.
     *
     * This method loads and validates the workflow definition from the provided string,
     * initializes the caches by parsing the workflow, and then returns the Workflow object.
     *
     * @param definition The workflow definition string in YAML format.
     * @return The parsed and validated Workflow object.
     */
    internal fun parseWorkflow(definition: String): Workflow {
        // load and validate workflow definition
        val workflow = validation().read(definition, WorkflowFormat.YAML)
        // parse the workflow to init caches
        workflow.parseNodes()
        // cache and return the workflow itself
        return workflow
    }

    /**
     * Caches the task nodes of the given workflow.
     *
     * This method processes the task nodes starting from the currentNodeInstance node,
     * and stores them in the `nodesCache` for the given workflow.
     *
     */
    private fun Workflow.parseNodes() {
        // recursively creates Nodes
        val root = Node(
            position = NodePosition.root,
            task = RootTask(`do`, use).also {
                it.output = output
                it.input = input
            },
            name = "workflow",
            parent = null,
        )

        // Initialize cache
        nodesCache[index] = mutableMapOf<JsonPointer, Node<*>>().apply {
            fun processNode(node: Node<*>) {
                put(node.position.jsonPointer, node)
                node.children?.forEach { processNode(it) }
            }
            processNode(root)
        }
    }

    fun error(message: String): Nothing = throw IllegalStateException(message)

    companion object {
        internal val workflowCache = ConcurrentHashMap<WorkflowIndex, Workflow>()
        internal val secretsCache = ConcurrentHashMap<WorkflowIndex, Map<String, JsonElement>>()
        internal val nodesCache = ConcurrentHashMap<WorkflowIndex, Map<JsonPointer, Node<*>>>()
    }
}

internal val Workflow.index: WorkflowIndex
    get() = document.name to document.version