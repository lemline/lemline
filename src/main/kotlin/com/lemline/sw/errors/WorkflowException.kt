package com.lemline.sw.errors

import com.lemline.sw.tasks.NodeInstance
import com.lemline.sw.tasks.flows.DoInstance
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

class WaitWorkflowException : WorkflowException()