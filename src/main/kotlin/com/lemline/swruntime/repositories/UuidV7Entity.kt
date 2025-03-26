package com.lemline.swruntime.repositories

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import java.util.*

@MappedSuperclass
internal abstract class UuidV7Entity : PanacheEntityBase {
    @Id
    @GeneratedValue
    @UuidV7
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID? = null
}

internal interface UuidV7Repository<T : UuidV7Entity> : PanacheRepositoryBase<T, UUID>