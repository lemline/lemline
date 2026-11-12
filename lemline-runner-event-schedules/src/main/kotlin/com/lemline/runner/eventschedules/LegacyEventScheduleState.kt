// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import kotlinx.serialization.Serializable

/** Read-only compatibility with the pre-V906 definition-wide payload. */
@Serializable
internal data class LegacyEventScheduleState(val groups: List<LegacyEventScheduleGroup> = emptyList())

@Serializable
internal data class LegacyEventScheduleGroup(
    val correlations: Map<String, String> = emptyMap(),
    val events: List<String> = emptyList(),
    val matched: Set<Int> = emptySet(),
    val termination: Set<Int> = emptySet(),
)
