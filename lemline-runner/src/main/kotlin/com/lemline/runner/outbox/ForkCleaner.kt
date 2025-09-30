// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.outbox.bases.Cleaner
import com.lemline.runner.outbox.bases.Scheduler
import com.lemline.runner.repositories.ForkRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkCleaner : Scheduler() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var forkRepository: ForkRepository
    
    override val description: String = "Forks table cleaning"

    override val schedulable by lazy {
        Cleaner(
            cleanerRepository = forkRepository,
            cleanerConfig = lemlineConfig.database().tables().forks().cleanup(),
        )
    }
}
