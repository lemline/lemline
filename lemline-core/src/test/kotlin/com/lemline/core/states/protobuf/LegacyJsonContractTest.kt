// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.lemline.common.json.parseJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.messages.internal.v1.WorkflowStateProto
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class LegacyJsonContractTest {
    @Test
    fun `Jackson runtime reads and writes the exact pre-migration message bytes`() {
        // Produced by commit bba9a3f6 using kotlinx.serialization trees, before migration.
        val legacy = Base64.getDecoder().decode("CoADCv0CCtcBCtQBEtEBCs4BCgYIpevcygYSJDAxOTAwMDAwLTAwMDAtNzAwMC04MDAwLTAwMDAwMDAwMDAwMRqbASqYAQoKCgRudWxsEgIIAAoLCgVlbXB0eRICMgAKDAoGb2JqZWN0EgIqAAoLCgVmYWxzZRICIAAKEQoEemVybxIJEQAAAAAAAAAAChQKB2RlY2ltYWwSCREAAAAAAAD0PwoPCgR0ZXh0EgcaBcOp4piVCigKBm5lc3RlZBIeMhwKFioUChIKBXZhbHVlEgkRAAAAAAAARUAKAggAIgASAy9kbxqbASqYAQoKCgRudWxsEgIIAAoLCgVlbXB0eRICMgAKDAoGb2JqZWN0EgIqAAoLCgVmYWxzZRICIAAKEQoEemVybxIJEQAAAAAAAAAAChQKB2RlY2ltYWwSCREAAAAAAAD0PwoPCgR0ZXh0EgcaBcOp4piVCigKBm5lc3RlZBIeMhwKFioUChIKBXZhbHVlEgkRAAAAAAAARUAKAggA")
        val state = StepByStepOrchestrator.initCmd(
            workflowId = WorkflowId(IDV7.from("01900000-0000-7000-8000-000000000001")),
            workflowInput = parseJson("""{"null":null,"empty":[],"object":{},"false":false,"zero":0,"decimal":1.25,"text":"é☕","nested":[{"value":42},null]}"""),
            startedAt = Instant.parse("2026-01-02T03:04:05Z"),
        ).copy(createdAt = null) // pre-migration commands have no created_at: it decodes as unknown
        assertEquals(state, WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(legacy)))
        assertContentEquals(legacy, WorkflowStateProtobufMapper.toProto(state).encode())
    }
}
