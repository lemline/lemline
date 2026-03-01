// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.store.postgres

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.store.ConversationStateStore
import javax.sql.DataSource
import kotlinx.serialization.json.Json

class PostgresConversationStateStore(
    private val dataSource: DataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : ConversationStateStore {

    fun ensureSchema() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(CREATE_TABLE_SQL).use { it.execute() }
        }
    }

    override suspend fun load(sessionId: String): ConversationState? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(SELECT_SQL).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return null
                    }
                    val payload = resultSet.getString("payload")
                    return json.decodeFromString(payload)
                }
            }
        }
    }

    override suspend fun save(state: ConversationState): ConversationState {
        val payload = json.encodeToString(state)
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_SQL).use { statement ->
                statement.setString(1, state.sessionId)
                statement.setLong(2, state.revision)
                val parentRevisionId = state.parentRevisionId
                if (parentRevisionId == null) {
                    statement.setObject(3, null)
                } else {
                    statement.setLong(3, parentRevisionId)
                }
                statement.setString(4, state.status.name)
                statement.setString(5, payload)
                statement.executeUpdate()
            }
        }
        return state
    }

    override suspend fun delete(sessionId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(DELETE_SQL).use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS lemline_ai_workflow_conversations (
                session_id TEXT PRIMARY KEY,
                revision BIGINT NOT NULL,
                parent_revision_id BIGINT NULL,
                status TEXT NOT NULL,
                payload JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
        """

        const val SELECT_SQL = """
            SELECT payload
            FROM lemline_ai_workflow_conversations
            WHERE session_id = ?
        """

        const val UPSERT_SQL = """
            INSERT INTO lemline_ai_workflow_conversations (
                session_id,
                revision,
                parent_revision_id,
                status,
                payload,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), NOW(), NOW())
            ON CONFLICT (session_id) DO UPDATE SET
                revision = EXCLUDED.revision,
                parent_revision_id = EXCLUDED.parent_revision_id,
                status = EXCLUDED.status,
                payload = EXCLUDED.payload,
                updated_at = NOW()
        """

        const val DELETE_SQL = """
            DELETE FROM lemline_ai_workflow_conversations
            WHERE session_id = ?
        """
    }
}
