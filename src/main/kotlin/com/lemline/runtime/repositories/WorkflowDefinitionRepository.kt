package com.lemline.runtime.repositories

import com.lemline.runtime.models.WorkflowDefinition
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
internal class WorkflowDefinitionRepository : UuidV7Repository<WorkflowDefinition> {
    fun findByNameAndVersion(name: String, version: String): WorkflowDefinition? =
        find("name = ?1 and version = ?2", name, version).firstResult()
}
