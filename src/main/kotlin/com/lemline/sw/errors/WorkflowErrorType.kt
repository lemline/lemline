package com.lemline.sw.errors

import com.fasterxml.jackson.annotation.JsonValue

/**
 * Enum representing different types of workflow errors.
 *
 * @property type The string representation of the error type.
 * @property defaultStatus The default HTTP status code associated with the error type.
 *
 * cf. https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#standard-error-types
 */
enum class WorkflowErrorType(val type: String, val defaultStatus: Int) {
    CONFIGURATION("configuration", 400),
    VALIDATION("validation", 400),
    EXPRESSION("expression", 400),
    AUTHENTICATION("authentication", 401),
    AUTHORIZATION("authorization", 403),
    TIMEOUT("timeout", 408),
    COMMUNICATION("communication", 500),
    RUNTIME("runtime", 500);

    @JsonValue
    fun toJson(): String = type
}