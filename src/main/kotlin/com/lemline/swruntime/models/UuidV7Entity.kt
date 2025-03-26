package com.lemline.swruntime.models

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.persistence.*
import java.util.*

@MappedSuperclass
abstract class UuidV7Entity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID? = null
}

interface UuidV7Repository<T : UuidV7Entity> : PanacheRepositoryBase<T, UUID> 