package com.lemline.worker.models

import com.lemline.worker.repositories.UuidV7Entity
import jakarta.persistence.*

@Entity
@Table(
    name = "workflows",
    uniqueConstraints = [UniqueConstraint(name = "uk_workflows_name_version", columnNames = ["name", "version"])]
)
class WorkflowModel : UuidV7Entity() {

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