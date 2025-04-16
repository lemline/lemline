package com.lemline.worker.repositories

import com.github.f4b6a3.uuid.UuidCreator
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.IdentifierGenerator
import java.io.Serializable

/**
 * Custom Hibernate identifier generator that produces time-ordered UUIDs (UUIDv7).
 * This generator returns UUIDs as String values to ensure compatibility with all database types.
 */
class TimeOrderedUuidGenerator : IdentifierGenerator {
    override fun generate(session: SharedSessionContractImplementor, obj: Any): Serializable {
        return UuidCreator.getTimeOrderedEpoch().toString()
    }
} 