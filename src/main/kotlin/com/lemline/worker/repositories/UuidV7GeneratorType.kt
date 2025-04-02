package com.lemline.worker.repositories

import com.github.f4b6a3.uuid.UuidCreator
import org.hibernate.annotations.IdGeneratorType
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.generator.BeforeExecutionGenerator
import org.hibernate.generator.EventType
import org.hibernate.generator.EventTypeSets
import java.util.*

@IdGeneratorType(UuidV7GeneratorType.Generator::class)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class UuidV7

internal class UuidV7GeneratorType {
    class Generator : BeforeExecutionGenerator {
        override fun getEventTypes(): EnumSet<EventType> = EventTypeSets.INSERT_ONLY

        override fun generate(
            session: SharedSessionContractImplementor,
            owner: Any,
            currentValue: Any?,
            eventType: EventType?
        ): UUID = (currentValue as UUID?) ?: UuidCreator.getTimeOrderedEpoch()
    }
}