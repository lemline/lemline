package com.lemline.swruntime.models

import jakarta.persistence.*

@Entity
@Table(
    name = "workflow_definitions",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_workflow_definitions_name_version",
            columnNames = ["name", "version"]
        )
    ]
)
class WorkflowDefinition : UuidV7Entity() {

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var version: String

    @Column(nullable = false, columnDefinition = "TEXT")
    lateinit var definition: String

    @Version
    @Column(name = "version_number")
    var versionNumber: Long = 0
} 