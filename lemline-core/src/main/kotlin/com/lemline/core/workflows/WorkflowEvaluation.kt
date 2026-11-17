// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.expressions.EvaluationMode
import io.serverlessworkflow.api.types.Evaluate
import io.serverlessworkflow.api.types.Workflow

val Workflow.evaluationMode: EvaluationMode
    get() = when (evaluate?.mode) {
        Evaluate.EvaluateMode.LOOSE -> EvaluationMode.LOOSE
        else -> EvaluationMode.STRICT
    }
