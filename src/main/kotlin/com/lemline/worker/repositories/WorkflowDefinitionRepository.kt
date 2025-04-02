package com.lemline.worker.repositories

import com.lemline.worker.models.WorkflowDefinition
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class WorkflowDefinitionRepository : UuidV7Repository<WorkflowDefinition> {
    fun findByNameAndVersion(name: String, version: String): WorkflowDefinition? =
        find("name = ?1 and version = ?2", name, version).firstResult()

    @Transactional
    fun WorkflowDefinition.save() {
        persist()
    }
}
