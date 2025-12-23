// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy as CoreListenStrategy
import com.lemline.core.processors.UntilCondition
import com.lemline.core.workflows.CachedUntilCondition

/**
 * Database representation of listen strategy.
 *
 * This enum distinguishes between all possible listen task configurations:
 * - `ONE`: Single event, immediate completion
 * - `ANY`: First matching event from multiple filters, immediate completion
 * - `ANY_UNTIL_EXPR`: Accumulate events until expression condition is met
 * - `ANY_UNTIL_EVENT`: Accumulate events until termination event is received
 * - `ALL`: Wait for one event per filter
 */
enum class ListenerStrategy {
    /** Wait for a single event matching the filter */
    ONE,

    /** Wait for first event matching any filter, immediate completion */
    ANY,

    /** Accumulate events until expression condition is met */
    ANY_UNTIL_EXPR,

    /** Accumulate events until termination event is received */
    ANY_UNTIL_EVENT,

    /** Wait for one event per filter */
    ALL;

    companion object {
        /**
         * Converts from core ListenStrategy + until condition to database ListenerStrategy.
         */
        fun from(config: ListenConfig): ListenerStrategy = when (config.strategy) {
            CoreListenStrategy.ONE -> ONE
            CoreListenStrategy.ANY -> when (config.until) {
                is UntilCondition.Expression -> ANY_UNTIL_EXPR
                is UntilCondition.Event -> ANY_UNTIL_EVENT
                null -> ANY
            }

            CoreListenStrategy.ALL -> ALL
        }

        /**
         * Converts from core ListenStrategy + cached until condition to database ListenerStrategy.
         */
        fun from(strategy: CoreListenStrategy, until: CachedUntilCondition?): ListenerStrategy = when (strategy) {
            CoreListenStrategy.ONE -> ONE
            CoreListenStrategy.ANY -> when (until) {
                is CachedUntilCondition.Expression -> ANY_UNTIL_EXPR
                is CachedUntilCondition.Event -> ANY_UNTIL_EVENT
                null -> ANY
            }

            CoreListenStrategy.ALL -> ALL
        }
    }
}
