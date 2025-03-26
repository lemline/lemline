package com.lemline.swruntime.models

import com.github.f4b6a3.uuid.UuidCreator
import org.hibernate.annotations.IdGeneratorType
import org.hibernate.annotations.ValueGenerationType
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.generator.BeforeExecutionGenerator
import org.hibernate.generator.EventType
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.util.EnumSet
import java.util.UUID

@ValueGenerationType(generatedBy = UuidV7Generator::class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@IdGeneratorType(UuidV7Generator::class)
annotation class UuidV7

class UuidV7Generator : BeforeExecutionGenerator {
    override fun generate(
        session: SharedSessionContractImplementor,
        owner: Any,
        currentValue: Any?,
        eventType: EventType
    ): Any {
        return UuidCreator.getTimeOrderedEpoch()
    }

    override fun getEventTypes(): EnumSet<EventType> = EnumSet.of(EventType.INSERT)
} 