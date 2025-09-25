// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.github.zafarkhaja.semver.Version
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class Definitions() {

    @Inject
    private lateinit var definitionRepository: DefinitionRepository

    suspend fun get(
        workflowNamespace: WorkflowNamespace,
        workflowName: WorkflowName,
        workflowVersion: WorkflowVersion? = null
    ): Workflow? {

        if (workflowVersion == null) return get(workflowNamespace, workflowName)

        return DefinitionCache.getOrNull(workflowNamespace, workflowName, workflowVersion)
            ?: definitionRepository.findByNameAndVersion(workflowNamespace, workflowName, workflowVersion)
                ?.parseAndPut()
    }

    // parse the workflow definition and put it to the cache
    private fun DefinitionModel.parseAndPut(): Workflow = DefinitionCache.parseAndPut(definition)

    private suspend fun get(workflowNamespace: WorkflowNamespace, workflowName: WorkflowName): Workflow? {
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
