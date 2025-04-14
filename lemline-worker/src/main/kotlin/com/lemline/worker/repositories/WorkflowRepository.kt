package com.lemline.worker.repositories

import com.lemline.worker.models.WorkflowModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class WorkflowRepository : UuidV7Repository<WorkflowModel> {
    fun findByNameAndVersion(name: String, version: String): WorkflowModel? =
        find("name = ?1 and version = ?2", name, version).firstResult()

    @Transactional
    fun WorkflowModel.save() {
        persist()
    }
}
