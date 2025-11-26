package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowStepTest {

    @Test
    fun `parse valid workflow step with single task`() {
        val step = WorkflowStep("/do,0/taskA,0")
        assertEquals("/do,0/taskA,0", step.toJsonString())
    }

    @Test
    fun `parse valid workflow step with nested tasks`() {
        val step = WorkflowStep("/do,0/taskA,0/try,0/failing,0")
        assertEquals("/do,0/taskA,0/try,0/failing,0", step.toJsonString())
    }

    @Test
    fun `parse valid workflow step with foreach iteration`() {
        val step = WorkflowStep("/for,2/do,1/processItem,0")
        assertEquals("/for,2/do,1/processItem,0", step.toJsonString())
    }

    @Test
    fun `parse valid workflow step with multiple visit counts`() {
        val step = WorkflowStep("/for,5/do,3/task,2")
        assertEquals("/for,5/do,3/task,2", step.toJsonString())
    }

    @Test
    fun `toStaticPosition removes visit counts and returns NodePosition`() {
        val step = WorkflowStep("/do,0/taskA,0")
        val position = step.toNodePosition()
        assertEquals(NodePosition("/do/taskA"), position)
        assertEquals("/do/taskA", position.toString())
    }

    @Test
    fun `toStaticPosition removes visit counts from nested path`() {
        val step = WorkflowStep("/do,0/taskA,0/try,0/failing,0")
        val position = step.toNodePosition()
        assertEquals(NodePosition("/do/taskA/try/failing"), position)
        assertEquals("/do/taskA/try/failing", position.toString())
    }

    @Test
    fun `toStaticPosition removes visit counts with various counts`() {
        val step = WorkflowStep("/for,5/do,3/task,2")
        val position = step.toNodePosition()
        assertEquals(NodePosition("/for/do/task"), position)
        assertEquals("/for/do/task", position.toString())
    }

    @Test
    fun `toJsonString returns the path`() {
        val step = WorkflowStep("/do,0/taskA,0")
        assertEquals("/do,0/taskA,0", step.toJsonString())
    }

    @Test
    fun `fail when path does not start with slash`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("do,0/taskA,0")
        }
    }

    @Test
    fun `fail when segment missing comma separator`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("/do0/taskA,0")
        }
    }

    @Test
    fun `fail when visit count is not numeric`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("/do,abc/taskA,0")
        }
    }

    @Test
    fun `fail when visit count is negative`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("/do,-1/taskA,0")
        }
    }

    @Test
    fun `fail when name is empty`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("/,0/taskA,0")
        }
    }

    @Test
    fun `equality works correctly`() {
        val step1 = WorkflowStep("/do,0/taskA,0")
        val step2 = WorkflowStep("/do,0/taskA,0")
        val step3 = WorkflowStep("/do,1/taskA,0")

        assertEquals(step1, step2)
        assert(step1 != step3)
    }

    @Test
    fun `hashCode works correctly`() {
        val step1 = WorkflowStep("/do,0/taskA,0")
        val step2 = WorkflowStep("/do,0/taskA,0")

        assertEquals(step1.hashCode(), step2.hashCode())
    }

    @Test
    fun `toString returns path representation`() {
        val step = WorkflowStep("/do,0/taskA,0")
        assertEquals("/do,0/taskA,0", step.toJsonString())
    }

    @Test
    fun `JSON roundtrip serialization works`() {
        val original = WorkflowStep("/do,0/taskA,0")
        val json = LemlineJson.encodeToString(original)
        val decoded = LemlineJson.decodeFromString<WorkflowStep>(json)

        assertEquals(original, decoded)
    }

    @Test
    fun `JSON roundtrip with nested path`() {
        val original = WorkflowStep("/for,2/do,1/processItem,0")
        val json = LemlineJson.encodeToString(original)
        val decoded = LemlineJson.decodeFromString<WorkflowStep>(json)

        assertEquals(original, decoded)
    }

    @Test
    fun `fromJsonString deserializes correctly`() {
        val jsonString = "\"/do,0/taskA,0\""
        val step = WorkflowStep.fromJsonString(jsonString)
        assertEquals("/do,0/taskA,0", step.toJsonString())
    }

    @Test
    fun `different visit counts create different steps`() {
        val step1 = WorkflowStep("/do,0/taskA,0")
        val step2 = WorkflowStep("/do,1/taskA,0")
        val step3 = WorkflowStep("/do,0/taskA,1")

        assert(step1 != step2)
        assert(step1 != step3)
        assert(step2 != step3)
    }

    @Test
    fun `supports large visit counts`() {
        val step = WorkflowStep("/for,999/do,100/task,50")
        assertEquals("/for,999/do,100/task,50", step.toJsonString())
        assertEquals(NodePosition("/for/do/task"), step.toNodePosition())
    }

    @Test
    fun `deep nesting is supported`() {
        val step = WorkflowStep("/a,0/b,0/c,0/d,0/e,0/f,0")
        assertEquals("/a,0/b,0/c,0/d,0/e,0/f,0", step.toJsonString())
        assertEquals(NodePosition("/a/b/c/d/e/f"), step.toNodePosition())
    }

    @Test
    fun `round trip conversion between WorkflowStep and NodePosition`() {
        // Start with a WorkflowStep
        val step = WorkflowStep("/do,0/taskA,0")

        // Convert to NodePosition
        val position = step.toNodePosition()
        assertEquals(NodePosition("/do/taskA"), position)

        // The static position should not contain visit counts
        assertEquals("/do/taskA", position.toString())
    }
}
