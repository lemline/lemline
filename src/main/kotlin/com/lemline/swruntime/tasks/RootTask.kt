package com.lemline.swruntime.tasks

import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem

class RootTask(val `do`: List<TaskItem>) : TaskBase()