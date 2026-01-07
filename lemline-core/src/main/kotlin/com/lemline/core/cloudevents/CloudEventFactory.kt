// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.core.processors.EmitConfig
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import java.time.OffsetDateTime

object CloudEventFactory {

    fun build(config: EmitConfig): CloudEvent =
        CloudEventBuilder.v1().apply {
            withId(config.id)
            withSource(URI.create(config.source))
            withType(config.type)
            config.time?.let { withTime(OffsetDateTime.parse(it)) }
            config.subject?.let { withSubject(it) }
            config.dataschema?.let { withDataSchema(URI.create(it)) }
            config.datacontenttype?.let { withDataContentType(it) }
            config.data?.let { data ->
                val contentType = config.datacontenttype ?: "application/json"
                withDataContentType(contentType)
                withData(contentType, data.toString().toByteArray())
            }
            config.extensions?.forEach { (key, value) -> withExtension(key, value) }
        }.build()
}
