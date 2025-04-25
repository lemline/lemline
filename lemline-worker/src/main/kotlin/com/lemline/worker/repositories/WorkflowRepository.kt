// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.repositories

import com.lemline.worker.models.WorkflowModel
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class WorkflowRepository : PanacheRepositoryBase<WorkflowModel, String> {
    fun findByNameAndVersion(name: String, version: String): WorkflowModel? =
        find("name = ?1 and version = ?2", name, version).firstResult()

    @Transactional
    fun WorkflowModel.save() {
        persist(this)
    }
}
