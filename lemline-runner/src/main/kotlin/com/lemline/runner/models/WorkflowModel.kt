// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.repositories.UuidV7Entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version

@Entity
@Table(
    name = "workflows",
    uniqueConstraints = [UniqueConstraint(name = "uk_workflows_name_version", columnNames = ["name", "version"])],
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
