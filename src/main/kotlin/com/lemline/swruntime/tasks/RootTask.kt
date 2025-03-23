package com.lemline.swruntime.tasks

import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.Use

class RootTask(
    val `do`: List<TaskItem>,
    val use: Use?
) : TaskBase()