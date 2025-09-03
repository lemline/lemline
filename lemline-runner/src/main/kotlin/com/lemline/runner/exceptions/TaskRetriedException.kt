// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.exceptions

import com.lemline.core.errors.WorkflowException
import com.lemline.core.instances.TryInstance
import kotlin.time.ExperimentalTime

@ExperimentalTime
class TaskRetriedException(val tryInstance: TryInstance, override val cause: WorkflowException) : RuntimeException()
