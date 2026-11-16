// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.store.inmemory

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.store.ConversationStateStore
import java.util.concurrent.ConcurrentHashMap

class InMemoryConversationStateStore : ConversationStateStore {

    private val states = ConcurrentHashMap<String, ConversationState>()

    override suspend fun load(sessionId: String): ConversationState? = states[sessionId]

    override suspend fun save(state: ConversationState): ConversationState {
        states[state.sessionId] = state
        return state
    }

    override suspend fun delete(sessionId: String) {
        states.remove(sessionId)
    }
}
