package com.lemline.swruntime.expressions.scopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.serverlessworkflow.impl.json.JsonUtils

class Scope(val map: MutableMap<String, Any?> = mutableMapOf()) {
    fun setContext(context: ObjectNode) = map.set(CONTEXT, context)
    fun setInput(input: JsonNode) = map.set(INPUT, input)
    fun setOutput(output: JsonNode) = map.set(OUTPUT, output)
    fun setSecrets(secrets: Map<String, JsonNode>) = map.set(SECRETS, secrets)
    fun setAuthorization(authorization: AuthorizationDescriptor) = map.set(AUTHORIZATION, authorization)
    fun setTask(task: TaskDescriptor) = map.set(TASK, task)
    fun setWorkflow(workflow: WorkflowDescriptor) = map.set(WORKFLOW, workflow)
    fun setRuntime(runtime: RuntimeDescriptor) = map.set(RUNTIME, runtime)

    fun toJson() = JsonUtils.fromValue(map) as ObjectNode

    companion object {
        const val CONTEXT = "context"
        const val INPUT = "input"
        const val OUTPUT = "output"
        const val SECRETS = "secrets"
        const val AUTHORIZATION = "authorization"
        const val TASK = "task"
        const val WORKFLOW = "workflow"
        const val RUNTIME = "runtime"
    }
}
