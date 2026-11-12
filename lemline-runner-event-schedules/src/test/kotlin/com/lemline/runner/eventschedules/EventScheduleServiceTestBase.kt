// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowNamespace
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.definitions.DefinitionCacheSync
import com.lemline.runner.listeners.DefinitionListenService
import com.lemline.runner.starters.Starter
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import java.net.URI
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class EventScheduleServiceTestBase {
    abstract val db: DatabaseConfig
    private var namespace = WorkflowNamespace("events")
    private val definitions get() = DefinitionRepository().apply { databaseConfig = db }
    private val repository get() = EventScheduleRepository().apply {
        databaseConfig = db
        namespaceScope = object : NamespaceScope { override val configured = setOf(namespace.toString()) }
    }
    private fun service(repo: EventScheduleRepository = repository, definitionRepo: DefinitionRepository = definitions) = EventScheduleService().apply {
        databaseConfig = db
        definitions = definitionRepo
        repository = repo
        definitionListenService = DefinitionListenService()
    }

    @BeforeEach
    fun isolate() { namespace = WorkflowNamespace("events-${IDV7.random()}") }

    private suspend fun define(on: String, name: String = "trigger", ns: WorkflowNamespace = namespace): DefinitionModel {
        val definition = DefinitionModel.from("""
            document:
              dsl: '1.0.0'
              namespace: $ns
              name: $name
              version: '1.0.0'
            schedule:
              on:
        """.trimIndent() + "\n" + on.prependIndent("    ") + "\ndo:\n  - result:\n      set:\n        received: \${ \$workflow.input }\n")
        definitions.insert(definition) shouldBe 1
        WorkflowCache.parseYamlAndPut(definition.definition)
        return definition
    }

    private suspend fun refresh() = DefinitionCacheSync().apply {
        definitionRepository = definitions
    }.ensureInitialized()

    private fun event(type: String = "a", id: String = IDV7.random().toString(), source: String = "https://test.example", key: String = "x") =
        CloudEventBuilder.v1().withId(id).withSource(URI(source)).withType(type)
            .withData("application/json", "{\"key\":\"$key\"}".toByteArray()).build()

    private suspend fun starts() = repository.listAll().filter { it.workflowInfo.namespace == namespace }
    private fun EventScheduleModel.input() = instanceMessage.workflowState.nodeStack.rootState.workflowInput as JsonArray
    private fun EventScheduleModel.eventIds() = input().map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `routing reads only matching candidates and rechecks stale positives`() = runTest {
        val original = define("one:\n  with:\n    type: a")
        define("one:\n  with:\n    type: b", name = "other")
        val reads = spyk(definitions)
        val service = service(definitionRepo = reads)
        service.handleCloudEvent(event("unrelated"), namespace) shouldBe 0
        coVerify(exactly = 0) { reads.findForUpdate(any(), any()) }
        service.handleCloudEvent(event("a"), namespace) shouldBe 1
        coVerify(exactly = 1) { reads.findForUpdate(any(), any()) }
        coVerify(exactly = 0) { reads.listAll(any()) }
        coVerify(exactly = 0) { reads.listAllInNamespace(any(), any()) }

        definitions.update(original.copy(definition = original.definition.replace("type: a", "type: c"))) shouldBe 1
        // The old cached filter is only a candidate; the locked row rejects it and refreshes routing.
        service.handleCloudEvent(event("a"), namespace) shouldBe 0
        service.handleCloudEvent(event("c"), namespace) shouldBe 1
        definitions.delete(original) shouldBe 1
        service.handleCloudEvent(event("c"), namespace) shouldBe 0
        WorkflowCache.getEventSchedules(namespace).map { it.workflowInfo.name.toString() } shouldBe listOf("other")
    }

    @Test
    fun `one starts repeatedly with event envelopes and ignores redelivery`() = runTest {
        define("one:\n  with:\n    type: a")
        val a = event(id = "same")
        service().handleCloudEvent(a, namespace) shouldBe 1
        service().handleCloudEvent(a, namespace) shouldBe 0
        service().handleCloudEvent(event(id = "same", source = "/other"), namespace) shouldBe 1
        service().handleCloudEvent(event("unrelated"), namespace) shouldBe 0
        val starts = starts()
        starts.size shouldBe 2
        starts.map { it.workflowState.nodeStack.rootState.workflowId }.distinct().size shouldBe 2
        starts.first().input().first().jsonObject.getValue("data").jsonObject.getValue("key").jsonPrimitive.content shouldBe "x"
    }

    @Test
    fun `event only starts definitions in arrival namespace and all versions are eligible`() = runTest {
        val first = define("any: []")
        definitions.insert(DefinitionModel.from(first.definition.replace("version: '1.0.0'", "version: '2.0.0'"))) shouldBe 1
        define("any: []", "second")
        define("any: []", ns = WorkflowNamespace("foreign-${IDV7.random()}"))
        refresh()
        service().handleCloudEvent(event(), namespace) shouldBe 3
        starts().size shouldBe 3
    }

    @Test
    fun `all keeps repeated events in FIFO collections across service restarts`() = runTest {
        define("all:\n  - with: { type: a }\n  - with: { type: b }")
        service().handleCloudEvent(event("a", "a1"), namespace) shouldBe 0
        service().handleCloudEvent(event("a", "a2"), namespace) shouldBe 0
        service().handleCloudEvent(event("a", "a1"), namespace) shouldBe 0
        service().handleCloudEvent(event("b", "b1"), namespace) shouldBe 1
        service().handleCloudEvent(event("b", "b2"), namespace) shouldBe 1
        starts().map { it.eventIds() }.toSet() shouldBe setOf(listOf("a1", "b1"), listOf("a2", "b2"))
    }

    @Test
    fun `all respects correlation and rejects missing correlation values`() = runTest {
        define("""
            all:
              - with: { type: a }
                correlate:
                  key: { from: '${'$'}{ .key }' }
              - with: { type: b }
                correlate:
                  key: { from: '${'$'}{ .key }' }
        """.trimIndent())
        service().handleCloudEvent(event("a", "a1", key = "one"), namespace) shouldBe 0
        val missing = CloudEventBuilder.from(event("b")).withData("application/json", "{}".toByteArray()).build()
        service().handleCloudEvent(missing, namespace) shouldBe 0
        service().handleCloudEvent(event("b", "b2", key = "two"), namespace) shouldBe 0
        service().handleCloudEvent(event("b", "b1", key = "one"), namespace) shouldBe 1
        starts().single().eventIds() shouldBe listOf("a1", "b1")
        service().handleCloudEvent(event("a", "a2", key = "two"), namespace) shouldBe 1
        starts().size shouldBe 2
    }

    @Test
    fun `any until expression accumulates unique events then rearms`() = runTest {
        define("any:\n  - with: { type: a }\nuntil: '\${ length == 2 }'")
        val first = event(id = "a1")
        service().handleCloudEvent(first, namespace) shouldBe 0
        service().handleCloudEvent(first, namespace) shouldBe 0
        service().handleCloudEvent(event(id = "a2"), namespace) shouldBe 1
        starts().single().eventIds() shouldBe listOf("a1", "a2")
        service().handleCloudEvent(event(id = "a3"), namespace) shouldBe 0
        service().handleCloudEvent(event(id = "a4"), namespace) shouldBe 1
    }

    @Test
    fun `overlapping any filters create one start and nonmatching attributes are ignored`() = runTest {
        define("any:\n  - with: { type: a, source: https://test.example }\n  - with: { type: a, source: https://test.example }")
        service().handleCloudEvent(event(source = "/foreign"), namespace) shouldBe 0
        service().handleCloudEvent(event(), namespace) shouldBe 1
        starts().single().input().size shouldBe 1
    }

    @Test
    fun `one event matching multiple all filters appears once in workflow input`() = runTest {
        define("all:\n  - with: { type: a }\n  - with: { source: https://test.example }")
        service().handleCloudEvent(event(), namespace) shouldBe 1
        starts().single().input().size shouldBe 1
    }

    @Test
    fun `extension constraints reject missing and mismatched attributes`() = runTest {
        define("one:\n  with: { type: a, tenant: approved, priority: 3 }")
        service().handleCloudEvent(event(), namespace) shouldBe 0
        val wrong = CloudEventBuilder.from(event()).withExtension("tenant", "other").withExtension("priority", 3).build()
        service().handleCloudEvent(wrong, namespace) shouldBe 0
        val right = CloudEventBuilder.from(event()).withExtension("tenant", "approved").withExtension("priority", 3).build()
        service().handleCloudEvent(right, namespace) shouldBe 1
        starts().single().input().single().jsonObject.getValue("tenant").jsonPrimitive.content shouldBe "approved"
    }

    @Test
    fun `termination extension expressions do not close collections on unrelated events`() = runTest {
        define("any:\n  - with: { type: a }\nuntil:\n  one:\n    with:\n      type: stop\n      priority: '\${ . >= 3 }'")
        service().handleCloudEvent(event(), namespace) shouldBe 0
        val low = CloudEventBuilder.from(event("stop")).withExtension("priority", 2).build()
        service().handleCloudEvent(low, namespace) shouldBe 0
        starts().size shouldBe 0
        val high = CloudEventBuilder.from(event("stop")).withExtension("priority", 3).build()
        service().handleCloudEvent(high, namespace) shouldBe 1
    }

    @Test
    fun `invalid workflow input is consumed without a start and later valid input can trigger`() = runTest {
        val original = define("one:\n  with: { type: a }")
        val schema = """
            input:
              schema:
                format: json
                document:
                  type: array
                  items:
                    type: object
                    properties:
                      data:
                        type: object
                        properties:
                          key:
                            const: approved
        """.trimIndent()
        definitions.update(original.copy(definition = original.definition.replace("schedule:", "$schema\nschedule:"))) shouldBe 1
        val rejected = event(key = "rejected")
        service().handleCloudEvent(rejected, namespace) shouldBe 0
        service().handleCloudEvent(rejected, namespace) shouldBe 0
        starts().size shouldBe 0
        service().handleCloudEvent(event(key = "approved"), namespace) shouldBe 1
        starts().size shouldBe 1
    }

    @Test
    fun `any until all waits for every termination filter across restarts`() = runTest {
        define("any:\n  - with: { type: a }\nuntil:\n  all:\n    - with: { type: stop1 }\n    - with: { type: stop2 }")
        service().handleCloudEvent(event("a", "a1"), namespace) shouldBe 0
        service().handleCloudEvent(event("stop1"), namespace) shouldBe 0
        service().handleCloudEvent(event("stop2"), namespace) shouldBe 1
        starts().single().eventIds() shouldBe listOf("a1")
    }

    @Test
    fun `until all remembers termination events before the first main event and after rearming`() = runTest {
        define("any:\n  - with: { type: a }\nuntil:\n  all:\n    - with: { type: stop1 }\n    - with: { type: stop2 }")
        for (cycle in 1..2) {
            val early = event("stop1", "stop1-$cycle")
            service().handleCloudEvent(early, namespace) shouldBe 0
            service().handleCloudEvent(early, namespace) shouldBe 0
            service().handleCloudEvent(event("a", "a-$cycle"), namespace) shouldBe 0
            service().handleCloudEvent(event("stop2", "stop2-$cycle"), namespace) shouldBe 1
        }
        starts().map { it.eventIds() }.toSet() shouldBe setOf(listOf("a-1"), listOf("a-2"))
    }

    @Test
    fun `until one can terminate an empty collection`() = runTest {
        define("any:\n  - with: { type: a }\nuntil:\n  one:\n    with: { type: stop }")
        val stop = event("stop")
        service().handleCloudEvent(stop, namespace) shouldBe 1
        service().handleCloudEvent(stop, namespace) shouldBe 0
        starts().single().input().size shouldBe 0
    }

    @Test
    fun `a repeated termination slot is deduplicated even when it did not change collection state`() = runTest {
        define("any:\n  - with: { type: a }\nuntil:\n  all:\n    - with: { type: stop1 }\n    - with: { type: stop2 }")
        val redundant = event("stop1", "s2")
        service().handleCloudEvent(event("stop1", "s1"), namespace) shouldBe 0
        service().handleCloudEvent(redundant, namespace) shouldBe 0
        service().handleCloudEvent(event("stop2", "t1"), namespace) shouldBe 1
        service().handleCloudEvent(event("a", "a2"), namespace) shouldBe 0
        service().handleCloudEvent(redundant, namespace) shouldBe 0
        service().handleCloudEvent(event("stop2", "t2"), namespace) shouldBe 0
        starts().size shouldBe 1
        service().handleCloudEvent(event("stop1", "s3"), namespace) shouldBe 1
        starts().map { it.eventIds() }.toSet() shouldBe setOf(emptyList(), listOf("a2"))
    }

    @Test
    fun `definition updates reset pending collections and deletes remove registration`() = runTest {
        val original = define("all:\n  - with: { type: a }\n  - with: { type: b }")
        service().handleCloudEvent(event("a", "old"), namespace) shouldBe 0
        definitions.update(original.copy(definition = original.definition.replace("type: b", "type: c"))) shouldBe 1
        refresh()
        service().handleCloudEvent(event("c", "new-c"), namespace) shouldBe 0
        service().handleCloudEvent(event("a", "new-a"), namespace) shouldBe 1
        starts().single().eventIds() shouldBe listOf("new-c", "new-a")
        definitions.delete(original) shouldBe 1
        service().handleCloudEvent(event("a"), namespace) shouldBe 0
        service().handleCloudEvent(event("c"), namespace) shouldBe 0
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM lemline_event_schedule_state WHERE namespace = ?").use {
                it.setString(1, namespace.toString())
                it.executeQuery().use { rs -> rs.next(); rs.getInt(1) shouldBe 0 }
            }
        }
    }

    @Test
    fun `concurrent duplicate delivery produces a single durable start`() = runTest {
        define("one:\n  with: { type: a }")
        val event = event()
        (1..8).map { async(Dispatchers.IO) { service().handleCloudEvent(event, namespace) } }.awaitAll().sum() shouldBe 1
        starts().size shouldBe 1
    }

    @Test
    fun `outbox claims only exact namespace matches`() = runTest {
        val definition = define("any: []")
        WorkflowCache.getEventSchedules(namespace).size shouldBe 1
        WorkflowCache.getEventSchedules(namespace).single().consumption.wildcard shouldBe true
        service().handleCloudEvent(event(), namespace) shouldBe 1
        val foreignRepository = EventScheduleRepository().apply {
            databaseConfig = db
            namespaceScope = object : NamespaceScope {
                override val configured = setOf(namespace.toString().uppercase())
            }
        }
        db.withTransaction { conn ->
            foreignRepository.findEntitiesToProcess(3, 100, conn).size shouldBe 0
            repository.findEntitiesToProcess(3, 100, conn).size shouldBe 1
        }
    }

    @Test
    fun `failed start insertion rolls back receipt and collection progress`() = runTest {
        define("one:\n  with: { type: a }")
        val repo = spyk(repository)
        coEvery { repo.insert(any<EventScheduleModel>(), any()) } throws IllegalStateException("storage failure")
        val event = event()
        shouldThrow<IllegalStateException> { service(repo).handleCloudEvent(event, namespace) }
        starts().size shouldBe 0
        service().handleCloudEvent(event, namespace) shouldBe 1
        starts().size shouldBe 1
    }

    @Test
    fun `outbox retries the same command and receipt survives outbox cleanup`() = runTest {
        define("one:\n  with: { type: a }")
        val event = event()
        service().handleCloudEvent(event, namespace) shouldBe 1
        val original = starts().single()
        var brokerAvailable = false
        val sentIds = mutableListOf<IDV7?>()
        val sentMessages = mutableListOf<InstanceMessage<out WorkflowCommand>>()
        val emitter = object : CommandEmitter {
            override suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7?) {
                sentIds.add(idempotentKey)
                sentMessages.add(message)
                check(brokerAvailable) { "broker down" }
            }
        }
        val hook = mockk<LifecycleEventHook>(relaxed = true)
        val outbox = EventScheduleOutbox().apply {
            databaseConfig = db
            repository = this@EventScheduleServiceTestBase.repository
            commandEmitter = emitter
            lifecycleHook = hook
        }
        outbox.processEntities(100, 3, 0.seconds)
        repository.findById(original.id)!!.outboxAttemptCount shouldBe 1
        brokerAvailable = true
        repository.update(repository.findById(original.id)!!.apply {
            outboxDelayedUntil = kotlin.time.Clock.System.now() - 1.seconds
        })
        outbox.processEntities(100, 3, 0.seconds)
        sentIds shouldBe listOf(original.id, original.id)
        sentMessages shouldBe listOf(original.instanceMessage, original.instanceMessage)
        coVerify(exactly = 1) { hook.onWorkflowCreated(any(), any()) }
        repository.deleteById(original.id) shouldBe 1
        service().handleCloudEvent(event, namespace) shouldBe 0
        starts().size shouldBe 0
    }

    @Test
    fun `manual start uses the caller validation error instead of invalid schedule model`() = runTest {
        val definition = define("one:\n  with: { type: a }")
        val workflow = WorkflowCache.parseYaml(definition.definition)
        val lookup = mockk<Definitions>()
        coEvery { lookup.get(any(), any(), any()) } returns workflow
        val starter = Starter().apply { definitions = lookup }
        val error = shouldThrow<IllegalArgumentException> {
            starter.prepareWorkflow(WorkflowId.random(), namespace, definition.name, definition.version,
                JsonObject(emptyMap()), false, ZoneId.of("UTC")) { throw IllegalArgumentException(it) }
        }
        error.message!! shouldContain "starts automatically from matching CloudEvents"
        starts().size shouldBe 0
    }
}
