package com.lemline.runner.models

import com.lemline.common.ids.IdGenerator
import java.util.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class IDV7(val value: @Contextual UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): IDV7 = IDV7(IdGenerator.generateV7())

        fun from(id: IDV7): IDV7 = IDV7(IdGenerator.deriveUuidV7FromV7(id.value))
    }
}
