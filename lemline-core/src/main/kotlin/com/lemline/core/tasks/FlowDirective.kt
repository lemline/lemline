// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.tasks

import io.serverlessworkflow.api.types.FlowDirectiveEnum.CONTINUE
import io.serverlessworkflow.api.types.FlowDirectiveEnum.END
import io.serverlessworkflow.api.types.FlowDirectiveEnum.EXIT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents different transition options for a workflow.
 *
 * A FlowDirective can be either:
 * - An enumeration value ([FlowDirectiveEnum]): continue, exit, or end
 * - A string ([FlowDirectiveGoto]): representing a named task to navigate to
 *
 * This is the Kotlin equivalent of `io.serverlessworkflow.api.types.FlowDirective`.
 */
@Serializable
sealed interface FlowDirective

/**
 * Standard flow directives for workflow control flow.
 */
@Serializable
@SerialName("directiveEnum")
sealed class FlowDirectiveEnum : FlowDirective {
    /**
     * Continue to the next task in sequence (default behavior).
     */
    @Serializable
    @SerialName("continue")
    data object Continue : FlowDirectiveEnum()

    /**
     * Exit the current node and continue with the parent.
     */
    @Serializable
    @SerialName("exit")
    data object Exit : FlowDirectiveEnum()

    /**
     * End the workflow execution.
     */
    @Serializable
    @SerialName("end")
    data object End : FlowDirectiveEnum()
}

/**
 * A flow directive that represents a goto operation to a named task.
 *
 * @property target The name of the task to navigate to.
 */
@Serializable
@SerialName("goto")
data class FlowDirectiveGoto(val target: String) : FlowDirective

/**
 * Converts this Kotlin [FlowDirective] to the Java SDK's [io.serverlessworkflow.api.types.FlowDirective].
 *
 * @return The equivalent Java SDK FlowDirective instance.
 */
fun FlowDirective.toJava(): io.serverlessworkflow.api.types.FlowDirective =
    io.serverlessworkflow.api.types.FlowDirective().apply {
        when (this@toJava) {
            is FlowDirectiveEnum.Continue -> flowDirectiveEnum = CONTINUE
            is FlowDirectiveEnum.Exit -> flowDirectiveEnum = EXIT
            is FlowDirectiveEnum.End -> flowDirectiveEnum = END
            is FlowDirectiveGoto -> string = this@toJava.target
        }
    }

/**
 * Converts the Java SDK's [io.serverlessworkflow.api.types.FlowDirective] to the Kotlin [FlowDirective].
 *
 * @return The equivalent Kotlin FlowDirective instance.
 * @throws IllegalArgumentException if the FlowDirective cannot be converted.
 */
fun io.serverlessworkflow.api.types.FlowDirective.toKotlin(): FlowDirective {
    return when (val value = this.get()) {
        is io.serverlessworkflow.api.types.FlowDirectiveEnum -> when (value) {
            CONTINUE -> FlowDirectiveEnum.Continue
            EXIT -> FlowDirectiveEnum.Exit
            END -> FlowDirectiveEnum.End
        }

        is String -> FlowDirectiveGoto(value)
        else -> throw IllegalArgumentException("Unknown FlowDirective type: $value")
    }
}
