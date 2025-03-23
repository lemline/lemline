package com.lemline.swruntime.errors

import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.flows.DoInstance
import kotlin.time.Duration

sealed class WorkflowException : RuntimeException()

class UncaughtWorkflowException(
    val error: WorkflowError,
    val attemptIndex: Int
) : WorkflowException()


class RetryWorkflowException(
    val nodeInstance: NodeInstance<*>,
    val error: WorkflowError,
    val attemptIndex: Int,
    val delay: Duration
) : WorkflowException()

class CaughtDoWorkflowException(
    val `do`: DoInstance,
) : WorkflowException()