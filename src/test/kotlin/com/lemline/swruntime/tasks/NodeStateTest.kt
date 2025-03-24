package com.lemline.swruntime.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NodeStateTest {

    /**
     * This test verifies that the constant values in NodeState don't change.
     * These constants are used as keys for serialization/deserialization
     * and changing them would break backward compatibility.
     */
    @Test
    fun `test constants should maintain their values`() {
        // These assertions will fail if someone changes the constants,
        // providing a reminder about the implications of such changes
        assertEquals(
            "child", NodeState.CHILD_INDEX,
            "NodeState.CHILD_INDEX constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "var", NodeState.VARIABLES,
            "NodeState.VARIABLES constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "in", NodeState.RAW_INPUT,
            "NodeState.RAW_INPUT constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "out", NodeState.RAW_OUTPUT,
            "NodeState.RAW_OUTPUT constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "ctx", NodeState.CONTEXT,
            "NodeState.CONTEXT constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "id", NodeState.WORKFLOW_ID,
            "NodeState.WORKFLOW_ID constant should not be changed as it would break serialization compatibility"
        )

        assertEquals(
            "at", NodeState.STARTED_AT,
            "NodeState.STARTED_AT constant should not be changed as it would break serialization compatibility"
        )
    }
}