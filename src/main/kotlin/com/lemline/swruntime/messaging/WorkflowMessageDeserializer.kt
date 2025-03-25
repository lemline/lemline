package com.lemline.swruntime.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer

class WorkflowMessageDeserializer : ObjectMapperDeserializer<WorkflowMessage> {
    constructor() : super(WorkflowMessage::class.java)
    constructor(objectMapper: ObjectMapper) : super(WorkflowMessage::class.java, objectMapper)
}