package com.lemline.swruntime.expressions.scopes

import com.fasterxml.jackson.databind.JsonNode
import io.serverlessworkflow.impl.json.JsonUtils
import net.thisptr.jackson.jq.Scope

/**
 * Data class representing the possible scope of an expression.
 *
 * @property context Optional map containing instanceContext values as JSON nodes.
 * @property input Optional instanceRawInput JSON node.
 * @property output Optional output JSON node.
 * @property secrets Map containing secret values as JSON nodes.
 * @property authorization Optional authorization descriptor.
 * @property task Optional task descriptor.
 * @property workflow Workflow descriptor.
 * @property runtime Runtime descriptor.
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#runtime-expression-arguments">Runtime Expression Arguments</a>
 *
 */
data class ExpressionScope(
    val context: Map<String, JsonNode>? = null,
    val input: JsonNode? = null,
    val output: JsonNode? = null,
    val secrets: Map<String, JsonNode>,
    val authorization: AuthorizationDescriptor? = null,
    val task: TaskDescriptor? = null,
    val workflow: WorkflowDescriptor,
    val runtime: RuntimeDescriptor
) {
    /**
     * Converts the expression scope to a Jackson JQ Scope.
     *
     * @return A new Scope instance populated with the expression scope values.
     */
    fun toScope(): Scope = Scope.newEmptyScope().apply {
        context?.let { setValue("instanceContext", JsonUtils.fromValue(it)) }
        input?.let { setValue("input", JsonUtils.fromValue(it)) }
        output?.let { setValue("output", JsonUtils.fromValue(it)) }
        setValue("secrets", JsonUtils.fromValue(secrets))
        authorization?.let { setValue("authorization", JsonUtils.fromValue(it)) }
        task?.let { setValue("task", JsonUtils.fromValue(it)) }
        setValue("workflow", JsonUtils.fromValue(workflow))
        setValue("runtime", JsonUtils.fromValue(runtime))
    }
}