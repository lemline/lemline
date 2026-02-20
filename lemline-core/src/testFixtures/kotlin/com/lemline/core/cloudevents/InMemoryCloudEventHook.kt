// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryCloudEventHook : CloudEventHook {

    private val logger = logger()
    private val channel = Channel<CloudEvent>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val _emittedEvents = mutableListOf<CloudEvent>()

    val emittedEvents: List<CloudEvent>
        get() = _emittedEvents.toList()

    override suspend fun emit(event: CloudEvent) {
        logger.debug { "InMemory: Emitting CloudEvent type=${event.type} source=${event.source}" }
        mutex.withLock { _emittedEvents.add(event) }
        channel.send(event)
    }

    override fun receive(): Flow<CloudEvent> {
        logger.debug { "InMemory: Creating receiver flow" }
        return channel.receiveAsFlow()
    }

    fun close() {
        logger.debug { "InMemory: Closing channel" }
        channel.close()
    }
}
