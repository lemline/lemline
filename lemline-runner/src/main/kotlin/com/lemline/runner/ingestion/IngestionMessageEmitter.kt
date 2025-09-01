// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val INGESTION_OUT_CHANNEL = "ingestion-out"

@Startup
@ApplicationScoped
internal class IngestionMessageEmitter : MessageEmitter() {

    @Channel(INGESTION_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
