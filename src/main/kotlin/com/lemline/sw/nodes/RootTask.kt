package com.lemline.sw.nodes

import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.Use

data class RootTask(
    val `do`: List<TaskItem>,
    val use: Use?
) : TaskBase()
