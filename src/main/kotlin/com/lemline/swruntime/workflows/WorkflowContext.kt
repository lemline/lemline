package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.swruntime.expressions.scopes.WorkflowDescriptor

data class WorkflowContext(
    val workflowDescriptor: WorkflowDescriptor,
    val workflowId: String,
    val rawInput: JsonNode,
    val currentContext: ObjectNode = JsonNodeFactory.instance.objectNode()
)
