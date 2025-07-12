// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.exceptions

import io.serverlessworkflow.api.types.RunWorkflow

class RunWorkflowStartedException(val runWorkflow: RunWorkflow) : RuntimeException()
