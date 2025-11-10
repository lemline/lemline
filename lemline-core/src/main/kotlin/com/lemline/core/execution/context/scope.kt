// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

typealias Scope = JsonObject

/**
 * Merges the current `Scope` map with another `Scope` map.
 * Entries from the other map will overwrite entries in the current map if the keys are the same.
 *
 * @param other Another `Scope` map to merge with the current map. If null, only the current map will be returned.
 * @return A new `Scope` map containing the merged key-value pairs from both maps.
 */
internal fun Scope.merge(other: Scope?): Scope =
    buildJsonObject {
        this@merge.forEach { put(it.key, it.value) }  // Put base scope first
        other?.forEach { put(it.key, it.value) }      // Then overwrite with other scope
    }
