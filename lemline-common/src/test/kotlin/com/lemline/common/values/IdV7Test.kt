// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.json.LemlineJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdV7Test {

    @Test
    fun `test random()`() {
        val id1: IDV7 = IDV7.random()
        val id2: IDV7 = IDV7.random()

        // equals should return true if the wrapped values are equal
        assertNotEquals(id1, id2)
    }

    @Test
    fun `test toString`() {
        val idstr = "019923d5-0d46-78ed-a2b5-8f54704b4a1e"

        val id1 = IDV7.from(idstr)
        val id2 = IDV7.from(idstr)
        val id3 = IDV7.random()

        // equals should return true if the wrapped values are equal
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)

        // toString should return the wrapped value
        assertEquals(idstr, id1.toString())
    }

    @Test
    fun `test JSON roundtrip with LemlineJson`() {
        val idstr = "019923d5-0d46-78ed-a2b5-8f54704b4a1e"
        val id = IDV7.from(idstr)

        val serialized: String = LemlineJson.encodeToString(id)

        // Encoded as JSON strings
        assertEquals("\"$idstr\"", serialized)

        // Decoding
        val decoded = LemlineJson.decodeFromString<IDV7>(serialized)

        assertEquals(id, decoded)
    }
}
