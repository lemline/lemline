// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cleaner

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.models.ForkModel
import com.lemline.runner.repositories.ForkRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * ForkCleaner specializes AbstractCleaner to clean up old fork waiting records.
 *
 * Fork waiting records are created when a workflow executes parallel branches in a fork.
 * When all branches complete (or first completes in compete mode), the fork record is marked
 * as SENT and scheduled for cleanup. This cleaner removes old SENT records to prevent database bloat.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkCleaner : AbstractCleaner<ForkModel>() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var repository: ForkRepository

    // Is this cleaner enabled?
    // Note: Fork cleanup uses parent config since fork is a type of parent-child relationship
    override val enabled by lazy {
        lemlineConfig.outbox().parent().enabled().getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().workflows().getOrNull()?.consumer()?.enabled() ?: false
    }

    // Cleanup configuration
    override val cleanerConf by lazy { lemlineConfig.outbox().fork().cleanup() }
}
