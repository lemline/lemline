package com.lemline.runner.ingestion

import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val INGESTION_OUT = "ingestion-out"

@Startup
@ApplicationScoped
internal class IngestionMessageEmitter : MessageEmitter() {

    @Channel(INGESTION_OUT)
    override lateinit var emitter: MutinyEmitter<String>
}
