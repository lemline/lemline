// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7

/** Metadata for one pending consumption, independently of its ordered event rows. */
data class EventScheduleCollection(
    val id: IDV7 = IDV7.random(),
    val index: Long,
    val correlations: MutableMap<String, String> = mutableMapOf(),
    val matched: MutableSet<Int> = mutableSetOf(),
    val termination: MutableSet<Int> = mutableSetOf(),
    var eventCount: Long = 0,
)
