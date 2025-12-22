// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.runner.common.cleaner.AbstractCleaner
import com.lemline.runner.common.config.CleanupConfig
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithCrudRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Cleaner for retry entities.
 * Deletes retry records that have completed or failed and exceeded their retention period.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class RetryCleaner : AbstractCleaner<RetryModel>() {

    @Inject
    lateinit var retryConfig: RetryConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var retryRepository: RetryRepository

    override val cleanerRepository: WithCleanerRepository<RetryModel> get() = retryRepository

    override val crudRepository: WithCrudRepository<RetryModel> get() = retryRepository

    override val enabled by lazy { retryConfig.enabled }

    override val cleanerConf: CleanupConfig by lazy { retryConfig.cleanup }

    override val jobName: String get() = "RetryCleaner"
}
