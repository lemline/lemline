// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.Export
import io.serverlessworkflow.api.types.Output
import io.serverlessworkflow.api.types.TaskBase

/**
 * Internal task representing a subscription iterator for Listen tasks.
 *
 * This task is NOT part of the Serverless Workflow SDK - it's an internal
 * structural node created when a ListenTask has a `foreach` configuration.
 *
 * ## Purpose
 *
 * Separates event iteration concerns from event subscription:
 * - **ListenTask**: Handles subscription lifecycle (waiting for events)
 * - **ForeachTask**: Handles per-event iteration with proper executionIndex
 *
 * ## Node Tree Structure
 *
 * When a ListenTask has foreach configured:
 * ```
 * ListenTask                    → /do/0/myListen
 *   └── ForeachTask             → /do/0/myListen/foreach
 *        └── DoTask             → /do/0/myListen/foreach/do
 *             └── Child tasks   → /do/0/myListen/foreach/do/0/childTask
 * ```
 *
 * ## ExecutionIndex Semantics
 *
 * - ListenTask.executionIndex: Node re-entry count (retry, loop)
 * - ForeachTask.executionIndex: Event iteration number (0, 1, 2...)
 *
 * This separation ensures executionKey has consistent semantics.
 *
 * @property itemVar Variable name for current event (default: "item")
 * @property indexVar Variable name for iteration index (default: "index")
 * @property doTask The do task to execute for each event
 * @property foreachOutput Per-item output transformation (optional)
 * @property foreachExport Per-item context export (optional)
 */
data class ForeachTask(
    val itemVar: String = "item",
    val indexVar: String = "index",
    val doTask: DoTask,
    private val foreachOutput: Output? = null,
    private val foreachExport: Export? = null,
) : TaskBase() {
    init {
        export = foreachExport
        output = foreachOutput
    }
}
