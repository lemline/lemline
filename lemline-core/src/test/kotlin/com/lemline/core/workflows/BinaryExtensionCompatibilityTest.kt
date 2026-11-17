// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.jsonValue
import com.lemline.core.processors.EventFilter
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BinaryExtensionCompatibilityTest {
    @Test
    fun `binary extensions match their previous base64 JSON string representation`() {
        val event = CloudEventBuilder.v1().withId("binary").withSource(URI("https://test.example"))
            .withType("binary").withExtension("digest", byteArrayOf(1, 2, 3)).build()
        for (expected in listOf("AQID", "\${ . == \"AQID\" }", "\${ type == \"string\" }")) {
            assertTrue(CachedEventFilter(EventFilter(), mapOf("digest" to jsonValue(expected))).matches(event))
        }
        assertFalse(CachedEventFilter(EventFilter(), mapOf("digest" to jsonValue("wrong"))).matches(event))
    }
}
