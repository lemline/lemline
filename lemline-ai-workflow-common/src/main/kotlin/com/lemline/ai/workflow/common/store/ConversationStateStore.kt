// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.common.store

import com.lemline.ai.workflow.common.model.ConversationState

interface ConversationStateStore {
    suspend fun load(sessionId: String): ConversationState?

    suspend fun save(state: ConversationState): ConversationState

    suspend fun delete(sessionId: String)
}
