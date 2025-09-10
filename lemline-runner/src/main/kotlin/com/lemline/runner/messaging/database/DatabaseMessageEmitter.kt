// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.runner.messaging.MessageEmitter
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Channel

internal const val DATABASE_OUT_CHANNEL = "ingestion-out"

@ExperimentalSerializationApi
@ExperimentalTime
@Startup
@ApplicationScoped
internal class DatabaseMessageEmitter : MessageEmitter<DatabaseMessage>() {
    @Channel(DATABASE_OUT_CHANNEL)
    override lateinit var emitter: MutinyEmitter<String>
}
