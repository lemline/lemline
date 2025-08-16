package com.lemline.common.flexible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable(with = FlexibleFieldSerializer::class)
class FlexibleField<T>(
    private val _serialized: String? = null,
    private val _parsed: T? = null,
    private val serializer: KSerializer<T>
) {
    init {
        require(_serialized != null || _parsed != null) {
            "At least one of _serialized or _parsed must be provided"
        }
    }

    constructor(serialized: String, serializer: KSerializer<T>) : this(
        _serialized = serialized,
        serializer = serializer
    )

    constructor(parsed: T, serializer: KSerializer<T>) : this(
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
