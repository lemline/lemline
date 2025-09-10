// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.flexible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A generic class that encapsulates a value that can either be in serialized string format
 * or in its object representation. It ensures that at least one of the representations
 * must be provided during initialization and avoids recalculations of the serialized or parsed value.
 *
 * @param T The type of the parsed value.
 * @property _serialized The serialized string representation of the value.
 * @property _parsed The parsed object representation of the value.
 * @property serializer The serializer used for encoding and decoding the value.
 * @constructor Initializes LazyParsedField with a serialized string and serializer.
 * @constructor Initializes LazyParsedField with a parsed value and serializer.
 * @throws IllegalArgumentException If neither the serialized nor the parsed value is provided.
 */
@Serializable(with = LazyParsedFieldSerializer::class)
class LazyParsedField<T>(
    private val _serialized: String? = null,
    private val _parsed: T? = null,
    private val serializer: KSerializer<T>
) {
    init {
        val nullCount = listOf(_serialized, _parsed).count { it == null }
        require(nullCount == 1) {
            if (nullCount == 0) "only one of _serialized and _parsed must be provided"
            else "At least one of _serialized or _parsed must be provided"
        }
    }

    override fun equals(other: Any?) = (other is LazyParsedField<T>) && serialized == other.serialized

    override fun hashCode() = serialized.hashCode()

    constructor(serialized: String, serializer: KSerializer<T>) : this(
        _serialized = serialized,
        serializer = serializer
    )

    constructor(parsed: T?, serializer: KSerializer<T>) : this(
        _parsed = parsed,
        serializer = serializer
    )

    val serialized: String by lazy {
        _serialized ?: Json.encodeToString(serializer, _parsed!!)
    }

    val parsed: T by lazy {
        _parsed ?: Json.decodeFromString(serializer, _serialized!!)
    }
}
