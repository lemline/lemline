package com.lemline.swruntime.models

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(name = "workflow_definitions", uniqueConstraints = [UniqueConstraint(columnNames = ["name", "version"])])
class WorkflowDefinition : PanacheEntity() {
    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var version: String

    @Column(nullable = false, columnDefinition = "TEXT")
    lateinit var definition: String
} 