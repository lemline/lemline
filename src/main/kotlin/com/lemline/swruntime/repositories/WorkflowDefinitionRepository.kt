package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.WorkflowDefinition
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
internal class WorkflowDefinitionRepository : UuidV7Repository<WorkflowDefinition> {
    fun findByNameAndVersion(name: String, version: String): WorkflowDefinition? =
        find("name = ?1 and version = ?2", name, version).firstResult()
}
