package com.lemline.core.execution.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

typealias ExprArgs = JsonObject

/**
 * Merges the current `ExprArgs` map with another `ExprArgs` map.
 * Entries from the other map will overwrite entries in the current map if the keys are the same.
 *
 * @param other Another `ExprArgs` map to merge with the current map. If null, only the current map will be returned.
 * @return A new `ExprArgs` map containing the merged key-value pairs from both maps.
 */
internal fun ExprArgs.merge(other: ExprArgs?): ExprArgs =
    buildJsonObject {
        other?.forEach { put(it.key, it.value) }
        this@merge.forEach { put(it.key, it.value) }
    }
