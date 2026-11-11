// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.listeners.ListenerConfig
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock

@Startup
@ApplicationScoped
class EventScheduleOutbox : AbstractOutbox<EventScheduleModel>() {
    @Inject public override lateinit var databaseConfig: DatabaseConfig
    @Inject public override lateinit var commandEmitter: CommandEmitter
    @Inject lateinit var repository: EventScheduleRepository
    @Inject lateinit var listenerConfig: ListenerConfig
    @Inject lateinit var lifecycleHook: LifecycleEventHook

    override val jobName = "Event schedules outbox"
    override val enabled get() = listenerConfig.enabled
    override val outboxConfig get() = listenerConfig.outbox
    override val outboxRepository get() = repository
    override val crudRepository get() = repository

    override suspend fun process(entity: EventScheduleModel) {
        commandEmitter.send(entity.instanceMessage, entity.id)
        lifecycleHook.onWorkflowCreated(entity.workflowInfo, entity.workflowState.nodeStack)
        entity.cleanupAfter = Clock.System.now()
    }
}
