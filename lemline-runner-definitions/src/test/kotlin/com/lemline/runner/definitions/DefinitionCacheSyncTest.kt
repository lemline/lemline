// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.lemline.common.values.info
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.WorkflowCache.getWorkflow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefinitionCacheSyncTest {
    private val repository = mockk<DefinitionRepository>()
    private val sync = DefinitionCacheSync().apply { definitionRepository = repository }
    private fun definition(type: String = "a") = DefinitionModel.from("""
        document:
          dsl: '1.0.0'
          namespace: cache-tests
          name: trigger
          version: '1.0.0'
        schedule:
          on:
            one:
              with: { type: $type }
        do:
          - waitForEvent:
              listen:
                to:
                  one:
                    with: { type: $type }
    """.trimIndent())

    @BeforeEach fun reset() = WorkflowCache.clear()

    @Test fun `sync adds updates and removes both kinds of filters`() = runTest {
        val original = definition()
        coEvery { repository.listAll() } returns listOf(original)
        sync.ensureInitialized()
        val workflow = WorkflowCache.getAllWorkflows().single()
        val info = workflow.info
        val schedule = WorkflowCache.getEventSchedules(info.namespace).single()
        sync.ensureInitialized()
        coVerify(exactly = 1) { repository.listAll() }
        sync.refresh()
        (info.getWorkflow() === workflow) shouldBe true
        (WorkflowCache.getEventSchedules(info.namespace).single() === schedule) shouldBe true

        coEvery { repository.listAll() } returns listOf(definition("b"))
        sync.refresh()
        WorkflowCache.getListenTasks(info).single().filters.single().standard.type shouldBe "b"
        WorkflowCache.getEventSchedules(info.namespace).single().consumption.filters.single().standard.type shouldBe "b"
        coEvery { repository.listAll() } returns emptyList()
        sync.refresh()
        info.getWorkflow() shouldBe null
        WorkflowCache.getAllListenTasks() shouldBe emptyList()
        WorkflowCache.getEventSchedules(info.namespace) shouldBe emptyList()
    }

    @Test fun `invalid replacement evicts old routing and synthetic workflows survive sync`() = runTest {
        val original = definition()
        coEvery { repository.listAll() } returns listOf(original)
        sync.ensureInitialized()
        val synthetic = WorkflowCache.parseYaml(original.definition.replace("name: trigger", "name: synthetic"))
        WorkflowCache.putWorkflow(synthetic)
        coEvery { repository.listAll() } returns listOf(original.copy(definition = "invalid: yaml"))
        sync.refresh()
        WorkflowCache.getAllWorkflows() shouldBe listOf(synthetic)
    }

    @Test fun `definition refresh replaces function filters and deletion removes routing`() = runTest {
        fun withFunction(type: String) = definition().let {
            it.copy(definition = it.definition + "\n" + """
                use:
                  functions:
                    awaitEvent:
                      listen:
                        to:
                          one:
                            with: { type: $type }
            """.trimIndent())
        }
        coEvery { repository.listAll() } returns listOf(withFunction("old"))
        sync.ensureInitialized()
        val parent = WorkflowCache.getAllWorkflows().single()
        WorkflowCache.getListenTasks(parent.info).filter { it.isFromFunction }.single().filters.single().standard.type shouldBe "old"
        coEvery { repository.listAll() } returns listOf(withFunction("new"))
        sync.refresh()
        WorkflowCache.getListenTasks(parent.info).filter { it.isFromFunction }.single().filters.single().standard.type shouldBe "new"
        coEvery { repository.listAll() } returns emptyList()
        sync.refresh()
        parent.info.getWorkflow() shouldBe null
        WorkflowCache.getListenTasks(parent.info) shouldBe emptyList()
    }

    @Test fun `failed warmup retries and concurrent first events share one load`() = runTest {
        coEvery { repository.listAll() } throws IllegalStateException("database unavailable")
        shouldThrow<IllegalStateException> { sync.ensureInitialized() }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { repository.listAll() } coAnswers {
            started.complete(Unit)
            release.await()
            listOf(definition())
        }
        val first = async { sync.ensureInitialized() }
        started.await()
        val second = async { sync.ensureInitialized() }
        release.complete(Unit)
        first.await()
        second.await()
        coVerify(exactly = 2) { repository.listAll() }
    }

    @Test fun `in flight snapshot cannot overwrite local update or resurrect deletion`() = runTest {
        val original = definition()
        val updated = definition("b")
        val initial = WorkflowCache.parseYamlAndPut(original.definition)
        var calls = 0
        coEvery { repository.listAll() } answers {
            calls++
            if (calls == 1) {
                WorkflowCache.parseYamlAndPut(updated.definition)
                listOf(original)
            } else listOf(updated)
        }
        sync.ensureInitialized()
        calls shouldBe 2
        WorkflowCache.getListenTasks(initial.info).single().filters.single().standard.type shouldBe "b"
        calls = 0
        coEvery { repository.listAll() } answers {
            calls++
            if (calls == 1) {
                WorkflowCache.remove(initial.info.namespace, initial.info.name, initial.info.version)
                listOf(updated)
            } else emptyList()
        }
        sync.refresh()
        calls shouldBe 2
        initial.info.getWorkflow() shouldBe null
    }
}
