package com.lemline.worker.repositories

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.GenericGenerator

@MappedSuperclass
abstract class UuidV7Entity : PanacheEntityBase {
    @Id
    @GeneratedValue(generator = "uuid7")
    @GenericGenerator(name = "uuid7", strategy = "com.lemline.worker.repositories.TimeOrderedUuidGenerator")
    @Column(name = "id", length = 36)
    var id: String? = null
}

internal interface UuidV7Repository<T : UuidV7Entity> : PanacheRepositoryBase<T, String>