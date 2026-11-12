// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

/** Event-derived correlations; absent collection keys remain eligible for auto-correlation. */
data class EventScheduleQueryKey(val correlations: Map<String, String>, val availableFilter: Int? = null)
