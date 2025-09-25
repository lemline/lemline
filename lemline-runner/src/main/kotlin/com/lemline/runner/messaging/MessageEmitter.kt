// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.json.JsonSerializable
import com.lemline.common.values.IDV7
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Message

internal abstract class MessageEmitter<T : JsonSerializable> {

    protected abstract val emitter: MutinyEmitter<String>

    @Inject
    private lateinit var messageMetaData: MessageMetaData

    suspend fun send(payload: String) {
        val md = MetaData(messageId = IDV7.random())
        emit(payload, md)
    }

    suspend fun send(msg: T) {
        val md = MetaData(messageId = IDV7.random())
        emit(msg.toJsonString(), md)
    }

    private suspend fun emit(payload: String, metadata: MetaData) {
        val msg = Message.of(payload)
        with(messageMetaData) { msg.addMetaData(metadata) }

        emitter.sendMessage(msg).awaitSuspending()
    }


}
