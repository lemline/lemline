// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import kotlin.time.ExperimentalTime

/**
 * Represents an event filter definition for a listen task.
 *
 * When a workflow definition containing listen tasks is registered, the filters
 * are extracted and stored in this table for efficient event routing. This allows
 * incoming CloudEvents to be matched against active listeners without parsing
 * the full workflow definition.
 *
 * One row is created per event filter in each listen task. For ALL strategy with
 * multiple filters, multiple rows are created with different filterIndex values.
 *
 * Event filter properties can be either:
 * - **Literal values**: For exact equality matching (e.g., `"com.example.order"`)
 * - **Runtime expressions**: For boolean filtering at event-arrival time (e.g., `${ .startswith("com.example") }`)
 *
 * @property id Unique identifier for this filter definition
 * @property listenId Reference to the parent listen task definition
 * @property filterIndex Index of this filter within the listen task (for ALL strategy ordering)
 * @property eventId CloudEvent id to match (literal only)
 * @property eventType CloudEvent type to match (literal only)
 * @property eventSource CloudEvent source URI (literal or expression)
 * @property eventSubject CloudEvent subject to match (literal only)
 * @property eventDatacontenttype CloudEvent datacontenttype to match (literal only)
 * @property eventDataschema CloudEvent dataschema URI (literal or expression)
 * @property eventTime CloudEvent time (literal or expression)
 * @property eventData CloudEvent data filter (literal for equality or expression for boolean)
 * @property correlations Serialized JSON map of correlation definitions
 */
@ExperimentalTime
data class DefinitionListenFilterModel(
    override val id: IDV7,
    val listenId: IDV7,
    val filterIndex: Int,
    val eventId: String?,
    val eventType: String?,
    val eventSource: String?,
    val eventSubject: String?,
    val eventDatacontenttype: String?,
    val eventDataschema: String?,
    val eventTime: String?,
    val eventData: String?,
    val correlations: String?
) : WithId {
    companion object
}
