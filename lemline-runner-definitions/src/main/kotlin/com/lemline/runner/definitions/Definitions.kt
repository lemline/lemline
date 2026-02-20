// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.github.zafarkhaja.semver.Version
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.workflows.WorkflowCache
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class Definitions {

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    suspend fun get(
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        workflowVersion: WorkflowVersion? = null
    ): Workflow? {

        if (workflowVersion == null) return getFromDatabase(workflowNamespace, workflowName)

        // get the workflow from the cache first, if not in cache, get from the database and update the cache
        return WorkflowCache.getWorkflow(workflowNamespace, workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowNamespace, workflowName, workflowVersion)
                ?.parseAndPut()
    }

    // parse the workflow definition and put it to the cache
    private fun DefinitionModel.parseAndPut(): Workflow = WorkflowCache.parseYamlAndPut(definition)

    private suspend fun getFromDatabase(workflowNamespace: WorkflowNamespace, workflowName: WorkflowName): Workflow? {
        // by name only, get the last version from the repository
        val workflows = definitionRepository.listByName(workflowNamespace, workflowName)
        if (workflows.isEmpty()) return null

        return workflows.maxWith { w1, w2 ->
            val v1 = w1.version.toString()
            val v2 = w2.version.toString()
            // compare using com.github.zafarkhaja.semver.Version
            // with fallback to string comparison
            runCatching {
                Version.parse(v1).compareTo(Version.parse(v2))
            }.getOrDefault(v1.compareTo(v2))
        }.parseAndPut()
    }
}
