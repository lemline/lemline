package com.lemline.swruntime.repositories

import com.lemline.swruntime.models.WorkflowDefinition
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WorkflowDefinitionRepository : PanacheRepository<WorkflowDefinition> {
    fun findByNameAndVersion(name: String, version: String): WorkflowDefinition? =
        find("name = ?1 and version = ?2", name, version).firstResult()
}
