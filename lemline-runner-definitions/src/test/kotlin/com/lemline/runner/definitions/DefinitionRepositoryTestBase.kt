// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.common.config.DatabaseConfig
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.common.runBlocking
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for DefinitionRepository tests.
 * Provides common test infrastructure for testing against different database types.
 *
 * Subclasses must provide:
 * - A DatabaseConfig implementation
 * - A DefinitionRepository with the config injected
 */
abstract class DefinitionRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): DefinitionRepository

    @BeforeEach
    fun setupTest() = runTest {
        getRepository().deleteAll()
    }

    @Test
    fun `should successfully persist and retrieve a complete workflow model with all properties`() = runTest {
        val repository = getRepository()
        val definitionModel = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = WorkflowName("test-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = "test-definition"
        )

        repository.insert(definitionModel)

        val retrievedModel =
            repository.findByNameAndVersion(definitionModel.namespace, definitionModel.name, definitionModel.version)
        retrievedModel shouldNotBe null
        retrievedModel?.namespace shouldBe definitionModel.namespace
        retrievedModel?.name shouldBe definitionModel.name
        retrievedModel?.version shouldBe definitionModel.version
        retrievedModel?.definition shouldContain definitionModel.definition
    }

    @Test
    fun `should return null when querying for a non-existent workflow name and version combination`() = runTest {
        val repository = getRepository()
        val result = repository.findByNameAndVersion(
            WorkflowNamespace("test"),
            WorkflowName("non-existent"),
            WorkflowVersion("1.0.0")
        )
        result shouldBe null
    }

    @Test
    fun `should successfully insert a new workflow version`() = runTest {
        val repository = getRepository()
        val original = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = WorkflowName("updatable-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = "original definition"
        )
        repository.insert(original)

        val updated = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = original.name,
            version = WorkflowVersion("1.1.0"),
            definition = "updated definition"
        )
        repository.insert(updated)

        val retrieved = repository.findByNameAndVersion(original.namespace, original.name, updated.version)
        retrieved shouldNotBe null
        retrieved!!.definition shouldBe "updated definition"
        retrieved.name shouldBe original.name
        retrieved.version shouldBe updated.version
        repository.countAll() shouldBe 2L
    }

    @Test
    fun `should successfully updating an existing workflow definition`() = runTest {
        val repository = getRepository()
        val original = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = WorkflowName("updatable-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = "original definition"
        )
        repository.insert(original)

        val updated = original.copy(definition = "updated definition")

        shouldNotThrowAny { repository.update(updated) }
        repository.findByNameAndVersion(
            original.namespace,
            original.name,
            original.version
        )?.definition shouldBe "updated definition"
        repository.countAll() shouldBe 1L
    }

    @Test
    fun `should fail inserting a new workflow with same name and version`() = runTest {
        val repository = getRepository()
        val original = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = WorkflowName("updatable-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = "original definition"
        )
        repository.insert(original)

        val updated = DefinitionModel(
            namespace = WorkflowNamespace("test"),
            name = original.name,
            version = original.version,
            definition = "updated definition",
        )

        repository.insert(updated) shouldBe 0

        val found = repository.findByNameAndVersion(original.namespace, original.name, original.version)
        found shouldNotBe null
        found!!.definition shouldBe original.definition
    }

    @Test
    fun `listByName should return all versions for a given name`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val name = WorkflowName("multi-version-workflow")
        val v1 = WorkflowVersion("1.0.0")
        val v2 = WorkflowVersion("2.0.0")
        val workflowV1 = DefinitionModel(namespace = namespace, name = name, version = v1, definition = "def-v1")
        val workflowV2 = DefinitionModel(namespace = namespace, name = name, version = v2, definition = "def-v2")
        val otherWorkflow = DefinitionModel(
            namespace = namespace,
            name = WorkflowName("other-workflow"),
            version = v1,
            definition = "def-other"
        )
        repository.insert(listOf(workflowV1, workflowV2, otherWorkflow))

        val results = repository.listByName(namespace, name)

        results shouldHaveSize 2
        results.find { it.version == v1 }?.definition shouldBe "def-v1"
        results.find { it.version == v2 }?.definition shouldBe "def-v2"
    }

    @Test
    fun `listByName should return an empty list if name does not exist`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val existingWorkflow =
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName("existing"),
                version = WorkflowVersion("1.0.0"),
                definition = "def"
            )
        repository.insert(existingWorkflow)

        val results = repository.listByName(namespace, WorkflowName("non-existent-name"))

        results shouldHaveSize 0
    }

    @Test
    fun `listByName should return a single item list if only one version exists for the name`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val name = WorkflowName("single-version-workflow")
        val v1 = WorkflowVersion("1.0.0")
        val workflowV1 = DefinitionModel(namespace = namespace, name = name, version = v1, definition = "def-v1")
        val otherWorkflow =
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName("another-workflow"),
                version = v1,
                definition = "def-other"
            )
        repository.insert(listOf(workflowV1, otherWorkflow))

        val results = repository.listByName(namespace, name)

        results shouldHaveSize 1
        results.first().version shouldBe v1
        results.first().definition shouldBe "def-v1"
    }

    @Test
    fun `should successfully insert a batch of workflows`() = runTest {
        val repository = getRepository()
        val workflows = List(5) { i ->
            DefinitionModel(
                namespace = WorkflowNamespace("test"),
                name = WorkflowName("batch-workflow-$i"),
                version = WorkflowVersion("1.0.0"),
                definition = "definition-$i"
            )
        }

        repository.insert(workflows)

        workflows.forEach { workflow ->
            val retrieved = repository.findByNameAndVersion(workflow.namespace, workflow.name, workflow.version)
            retrieved shouldNotBe null
            retrieved?.namespace shouldBe workflow.namespace
            retrieved?.name shouldBe workflow.name
            retrieved?.version shouldBe workflow.version
            retrieved?.definition shouldBe workflow.definition
        }
        repository.countAll() shouldBe workflows.size.toLong()
    }

    @Test
    fun `should successfully update a batch of workflows`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val originals = List(5) { i ->
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName("batch-workflow-$i"),
                version = WorkflowVersion("1.0.0"),
                definition = "definition-$i"
            )
        }
        repository.insert(originals)

        val updated = originals.mapIndexed { i, model -> model.copy(definition = "updated definition-$i") }

        shouldNotThrowAny { repository.update(updated) }
        originals.forEachIndexed { i, model ->
            repository.findByNameAndVersion(
                namespace,
                model.name,
                model.version
            )?.definition shouldBe "updated definition-$i"
        }
        repository.countAllInNamespace(namespace) shouldBe originals.size.toLong()
    }

    @Test
    fun `should successfully update a batch of workflows, returning the number of success`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val originals = List(5) { i ->
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName(" original-$i"),
                version = WorkflowVersion("1.0.0"),
                definition = "original-$i"
            )
        }
        repository.insert(originals)

        val newWorkflows = MutableList(5) { i ->
            when (i) {
                1, 3 -> DefinitionModel(
                    namespace = originals[i].namespace,
                    name = originals[i].name,
                    version = originals[i].version,
                    definition = "different-$i"
                )

                else -> DefinitionModel(
                    namespace = originals[i].namespace,
                    name = WorkflowName("different-$i"),
                    version = WorkflowVersion("1.0.0"),
                    definition = "different-$i"
                )
            }
        }

        repository.update(newWorkflows) shouldBe 2

        repository.countAllInNamespace(namespace) shouldBe originals.size.toLong()
    }

    @Test
    fun `should successfully insert a batch of workflows, returning the number of success`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val originals = List(5) { i ->
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName("original-$i"),
                version = WorkflowVersion("1.0.0"),
                definition = "original-$i"
            )
        }
        repository.insert(originals)

        val newWorkflows = MutableList(5) { i ->
            when (i) {
                1, 3 -> DefinitionModel(
                    namespace = originals[i].namespace,
                    name = originals[i].name,
                    version = originals[i].version,
                    definition = "different-$i"
                )

                else -> DefinitionModel(
                    namespace = originals[i].namespace,
                    name = WorkflowName("different-$i"),
                    version = WorkflowVersion("1.0.0"),
                    definition = "different-$i"
                )
            }
        }

        repository.insert(newWorkflows) shouldBe 3
        repository.countAllInNamespace(namespace) shouldBe 8
    }

    @Test
    fun `should retrieve definition by ID`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val workflow = DefinitionModel(
            namespace = namespace,
            name = WorkflowName("id-test-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = "test definition"
        )
        repository.insert(workflow)

        val retrieved = repository.findByNameAndVersion(workflow.namespace, workflow.name, workflow.version)

        retrieved shouldNotBe null
        retrieved?.namespace shouldBe workflow.namespace
        retrieved?.name shouldBe workflow.name
        retrieved?.version shouldBe workflow.version
        retrieved?.definition shouldBe workflow.definition
    }

    @Test
    fun `should retrieve all workflows in namespace`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val workflows = List(3) { i ->
            DefinitionModel(
                namespace = namespace,
                name = WorkflowName("list-workflow-$i"),
                version = WorkflowVersion("1.0.0"),
                definition = "definition-$i"
            )
        }
        repository.insert(workflows)

        val retrieved = repository.listAllInNamespace(namespace)

        retrieved shouldHaveSize workflows.size
        retrieved.toSet() shouldBe workflows.toSet()
    }

    @Test
    fun `listAll should retrieve workflows across all namespaces`() = runTest {
        val repository = getRepository()
        val namespace1 = WorkflowNamespace("namespace1")
        val namespace2 = WorkflowNamespace("namespace2")
        val namespace3 = WorkflowNamespace("namespace3")

        val workflow1 = DefinitionModel(
            namespace = namespace1,
            name = WorkflowName("workflow-a"),
            version = WorkflowVersion("1.0.0"),
            definition = "def1"
        )
        val workflow2 = DefinitionModel(
            namespace = namespace2,
            name = WorkflowName("workflow-b"),
            version = WorkflowVersion("1.0.0"),
            definition = "def2"
        )
        val workflow3 = DefinitionModel(
            namespace = namespace3,
            name = WorkflowName("workflow-c"),
            version = WorkflowVersion("1.0.0"),
            definition = "def3"
        )
        repository.insert(listOf(workflow1, workflow2, workflow3))

        val retrieved = repository.listAll()

        retrieved shouldHaveSize 3
        retrieved.map { it.namespace } shouldBe listOf(namespace1, namespace2, namespace3)
    }

    @Test
    fun `listAll should return empty list when no workflows exist`() = runTest {
        val repository = getRepository()

        val retrieved = repository.listAll()

        retrieved shouldHaveSize 0
    }

    @Test
    fun `listAll should return workflows sorted by namespace, name, version`() = runTest {
        val repository = getRepository()
        val namespaceA = WorkflowNamespace("a-namespace")
        val namespaceB = WorkflowNamespace("b-namespace")

        val workflows = listOf(
            DefinitionModel(namespaceB, WorkflowName("workflow-z"), WorkflowVersion("1.0.0"), "def1"),
            DefinitionModel(namespaceA, WorkflowName("workflow-b"), WorkflowVersion("2.0.0"), "def2"),
            DefinitionModel(namespaceA, WorkflowName("workflow-a"), WorkflowVersion("1.0.0"), "def3"),
            DefinitionModel(namespaceA, WorkflowName("workflow-b"), WorkflowVersion("1.0.0"), "def4"),
        )
        repository.insert(workflows)

        val retrieved = repository.listAll()

        retrieved shouldHaveSize 4
        // Should be sorted: a-namespace/workflow-a/1.0.0, a-namespace/workflow-b/1.0.0, a-namespace/workflow-b/2.0.0, b-namespace/workflow-z/1.0.0
        retrieved[0].namespace shouldBe namespaceA
        retrieved[0].name shouldBe WorkflowName("workflow-a")
        retrieved[1].namespace shouldBe namespaceA
        retrieved[1].name shouldBe WorkflowName("workflow-b")
        retrieved[1].version shouldBe WorkflowVersion("1.0.0")
        retrieved[2].namespace shouldBe namespaceA
        retrieved[2].name shouldBe WorkflowName("workflow-b")
        retrieved[2].version shouldBe WorkflowVersion("2.0.0")
        retrieved[3].namespace shouldBe namespaceB
    }

    @Test
    fun `listAllInNamespace should only return workflows from specified namespace`() = runTest {
        val repository = getRepository()
        val namespace1 = WorkflowNamespace("namespace1")
        val namespace2 = WorkflowNamespace("namespace2")

        val workflow1 = DefinitionModel(namespace1, WorkflowName("workflow-a"), WorkflowVersion("1.0.0"), "def1")
        val workflow2 = DefinitionModel(namespace1, WorkflowName("workflow-b"), WorkflowVersion("1.0.0"), "def2")
        val workflow3 = DefinitionModel(namespace2, WorkflowName("workflow-c"), WorkflowVersion("1.0.0"), "def3")
        repository.insert(listOf(workflow1, workflow2, workflow3))

        val retrieved = repository.listAllInNamespace(namespace1)

        retrieved shouldHaveSize 2
        retrieved.all { it.namespace == namespace1 } shouldBe true
    }

    @Test
    fun `should handle concurrent access safely`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val threadCount = 5
        val workflowsPerThread = 10
        val threads = List(threadCount) { threadIndex ->
            Thread {
                val workflowsToPersist = List(workflowsPerThread) { i ->
                    DefinitionModel(
                        namespace = namespace,
                        name = WorkflowName("concurrent-workflow-$threadIndex-$i"),
                        version = WorkflowVersion("1.0.0"),
                        definition = "definition-$threadIndex-$i"
                    )
                }
                runBlocking {
                    repository.insert(workflowsToPersist)

                    workflowsToPersist.forEach { workflow ->
                        val retrieved =
                            repository.findByNameAndVersion(workflow.namespace, workflow.name, workflow.version)
                        retrieved shouldNotBe null
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val allWorkflows = repository.listAllInNamespace(namespace)
        allWorkflows.size shouldBe (threadCount * workflowsPerThread)
    }

    @Test
    fun `delete should remove an existing workflow`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val workflow = DefinitionModel(
            namespace = namespace,
            name = WorkflowName("to-delete"),
            version = WorkflowVersion("1.0.0"),
            definition = "delete-me"
        )
        repository.insert(workflow)

        val deletedCount = repository.delete(workflow)

        deletedCount shouldBe 1
        repository.findByNameAndVersion(workflow.namespace, workflow.name, workflow.version) shouldBe null
    }

    @Test
    fun `delete should return 0 if workflow does not exist`() = runTest {
        val repository = getRepository()
        val namespace = WorkflowNamespace("test")
        val existingWorkflow = DefinitionModel(
            namespace = namespace,
            name = WorkflowName("existing"),
            version = WorkflowVersion("1.0.0"),
            definition = "def"
        )
        val nonExistentWorkflow = DefinitionModel(
            namespace = namespace,
            name = WorkflowName("non-existent"),
            version = WorkflowVersion("1.0.0"),
            definition = "def"
        )
        repository.insert(existingWorkflow)

        val deletedCount = repository.delete(nonExistentWorkflow)

        deletedCount shouldBe 0
        repository.findByNameAndVersion(
            existingWorkflow.namespace,
            existingWorkflow.name,
            existingWorkflow.version
        ) shouldNotBe null
    }
}
