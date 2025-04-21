// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import io.serverlessworkflow.api.types.Document
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.Use

data class RootTask(val document: Document, val `do`: List<TaskItem>, val use: Use?) : TaskBase()
