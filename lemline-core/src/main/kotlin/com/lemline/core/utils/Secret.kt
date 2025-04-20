package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.impl.expressions.ExpressionUtils

/**
 * Resolves a secret value based on the provided name.
 *
 * This function determines whether the given `name` is an expression or a direct reference to a secret.
 * - If `name` is an expression (e.g., wrapped in `${}`), it evaluates the expression and recursively resolves the secret.
 * - If `name` is a direct reference, it checks if the secret is defined in the workflow's configuration.
 *   - If the secret exists, its value is returned.
 *   - If the secret is not found, a `WorkflowErrorType.CONFIGURATION` error is thrown.
 * - If the `name` is neither an expression nor a valid secret reference, the `name` itself is returned as a fallback.
 *
 * @param name The name of the secret or an expression to evaluate.
 * @return The resolved secret value as a `String`.
 * @throws com.lemline.core.errors.WorkflowException if the secret is not found or if the expression evaluation fails.
 */
internal fun NodeInstance<*>.toSecret(name: String): String = when (ExpressionUtils.isExpr(name)) {
    true -> toSecret(evalString(transformedInput, ExpressionUtils.trimExpr(name), name))
    // if the name is the name of a secret in the workflow definition, return the value of the secret
    false -> when (name in rootInstance.secrets.keys) {
        true -> rootInstance.secrets[name]?.toString() ?: error(CONFIGURATION, "Secret '$name' not found")
        false -> name
    }
}