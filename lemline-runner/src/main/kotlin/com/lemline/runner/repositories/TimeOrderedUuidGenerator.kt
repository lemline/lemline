// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.github.f4b6a3.uuid.UuidCreator
import java.io.Serializable
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.IdentifierGenerator

/**
 * Custom Hibernate identifier generator that produces time-ordered UUIDs (UUIDv7).
 * This generator returns UUIDs as String values to ensure compatibility with all database types.
 */
@Suppress("unused")
class TimeOrderedUuidGenerator : IdentifierGenerator {
    override fun generate(session: SharedSessionContractImplementor, obj: Any): Serializable =
        UuidCreator.getTimeOrderedEpoch().toString()
}
