// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.fasterxml.jackson.annotation.JsonIgnore
import com.lemline.core.expressions.EvaluationMode
import io.serverlessworkflow.api.types.Document
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.Use

data class RootTask(
    val document: Document,
    val `do`: List<TaskItem>,
    val use: Use?,
    @get:JsonIgnore val evaluationMode: EvaluationMode = EvaluationMode.STRICT,
) : TaskBase()
