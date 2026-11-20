// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite.connector

import com.lemline.common.coroutines.ApplicationClassLoaderContext
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.sqlite.SqliteDatabase
import com.lemline.runner.messaging.sqlite.SqliteDelivery
import com.lemline.runner.messaging.sqlite.SqliteQueue
import io.smallrye.reactive.messaging.connector.InboundConnector
import io.smallrye.reactive.messaging.connector.OutboundConnector
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asPublisher
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.reactive.messaging.spi.Connector

/** SmallRye connector sharing a durable SQLite queue store between local runner processes. */
@ApplicationScoped
@Connector(SqliteConnector.NAME)
class SqliteConnector : InboundConnector, OutboundConnector {
    private val stores = ConcurrentHashMap<Path, SqliteQueue>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ApplicationClassLoaderContext())
    // Handlers still running at shutdown must be able to confirm their work and publish
    // its result. Only polling stops with [scope]; these short writes are never cancelled.
    private val settlement = CoroutineScope(SupervisorJob() + Dispatchers.IO + ApplicationClassLoaderContext())
    private val logger = logger()

    private fun Config.store(): SqliteQueue {
        val configuredPath = getConfigValue("path")
        val value = if (configuredPath.rawValue == null) SqliteDatabase.DEFAULT_PATH else configuredPath.value.orEmpty()
        val path = SqliteDatabase.resolve(value)
        return stores.computeIfAbsent(path, SqliteDatabase::initialize)
    }

    override fun getPublisher(cfg: Config): Flow.Publisher<out Message<*>> {
        val channel = cfg.getValue("channel-name", String::class.java)
        val queues = cfg.string("queues").orEmpty().split(",").map(String::trim).filter(String::isNotEmpty).distinct()
        val sources = queues.ifEmpty { listOf(cfg.string("queue") ?: channel) }
        val explicitDlq = if (queues.isEmpty()) cfg.string("dead-letter-queue") else null
        val deadLetterQueues = sources.associateWith { explicitDlq ?: "$it.dlq" }
        require(deadLetterQueues.values.none { it in sources }) { "A dead-letter queue cannot also be an input queue" }

        val ordered = cfg.getOptionalValue("ordered", Boolean::class.javaObjectType).orElse(false)
        val batchSize = cfg.positiveInt("batch-size", 10)
        val visibilitySeconds = cfg.positiveInt("visibility-timeout", 30)
        val pollIntervalMs = cfg.positiveInt("poll-interval", 50).toLong()
        // As in PGMQ, max-retries is the total number of delivery attempts.
        val maxAttempts = cfg.positiveInt("max-retries", 3)
        val store = cfg.store()

        val publisher = flow {
            // Flow subscriptions have their own jobs. Cancel blocked or idle subscribers
            // when the connector shuts down, and detach the callback on unsubscribe.
            val polling = currentCoroutineContext().job
            val shutdown = scope.coroutineContext.job.invokeOnCompletion { polling.cancel() }
            try {
                while (currentCoroutineContext().isActive) {
                    var received = false
                    for (queue in sources) {
                        val batch = try {
                            store.reserve(queue, batchSize, visibilitySeconds, ordered)
                        } catch (e: SQLException) {
                            logger.error(e) { "Could not reserve messages from SQLite queue '$queue'" }
                            continue
                        }
                        received = received || batch.isNotEmpty()
                        val dlq = deadLetterQueues.getValue(queue)
                        for (delivery in batch) {
                            deliveryMessage(store, delivery, dlq, visibilitySeconds, maxAttempts, ordered)?.let { emit(it) }
                        }
                    }
                    if (!received) delay(pollIntervalMs)
                }
            } finally {
                shutdown.dispose()
            }
        }

        return AdaptersToFlow.publisher(publisher.asPublisher(Dispatchers.IO + ApplicationClassLoaderContext()))
    }

    private suspend fun deliveryMessage(
        store: SqliteQueue,
        delivery: SqliteDelivery,
        dlq: String,
        visibilitySeconds: Int,
        maxAttempts: Int,
        ordered: Boolean,
    ): Message<String>? {
        // Backpressure may have let a prefetched lease expire. Do not charge it or
        // deliver to the handler. The write below also checks the lease under lock.
        val metadata = delivery.metadata
        if (metadata.visibilityTimeout <= Instant.now()) return null
        if (metadata.readCount >= maxAttempts) {
            quarantine(store, delivery, dlq, "Visibility timeout expired after $maxAttempts attempts")
            return null
        }
        val payload = try {
            delivery.payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            quarantine(store, delivery, dlq, "Unreadable message: invalid UTF-8")
            return null
        }
        val started = try {
            store.beginDelivery(delivery, visibilitySeconds) ?: return null
        } catch (e: SQLException) {
            logger.error(e) { "Could not begin delivery of SQLite message ${delivery.rowId}" }
            return null
        }
        return Message.of(payload, Metadata.of(started))
            .withAck { settlement.future { store.ack(delivery) }.thenApply { null } }
            .withNack { reason ->
                settlement.future {
                    if (started.readCount >= maxAttempts) {
                        store.deadLetter(delivery, dlq, reason.message ?: reason.javaClass.name)
                    } else {
                        store.release(delivery, immediately = ordered)
                    }
                }.thenApply { null }
            }
    }

    private suspend fun quarantine(store: SqliteQueue, delivery: SqliteDelivery, dlq: String, reason: String) {
        try {
            if (store.deadLetter(delivery, dlq, reason)) {
                logger.warn { "SQLite message ${delivery.rowId} moved to '$dlq': $reason" }
            }
        } catch (e: SQLException) {
            logger.error(e) { "Could not move SQLite message ${delivery.rowId} to '$dlq'; it remains recoverable" }
        }
    }

    override fun getSubscriber(cfg: Config): Flow.Subscriber<out Message<*>> {
        val channel = cfg.getValue("channel-name", String::class.java)
        val defaultQueue = cfg.string("queue") ?: channel
        val store = cfg.store()

        return object : Flow.Subscriber<Message<*>> {
            private lateinit var subscription: Flow.Subscription

            override fun onSubscribe(s: Flow.Subscription) {
                subscription = s
                s.request(1)
            }

            override fun onNext(message: Message<*>) {
                settlement.launch {
                    val metadata = message.getMetadata(SqliteOutgoingMetadata::class.java).getOrNull()
                    val queue = metadata?.queue ?: defaultQueue
                    val failure = try {
                        val payload = when (val value = message.payload) {
                            is ByteArray -> value
                            else -> value.toString().encodeToByteArray()
                        }
                        store.publish(
                            queue, metadata?.messageId ?: IDV7.random().toString(), payload, metadata?.delaySeconds ?: 0,
                        )
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Could not publish to SQLite queue '$queue'" }
                        e
                    }
                    try {
                        // Callbacks execute after the write, including on a deduplicated
                        // send. A callback failure cannot roll back the committed copy.
                        if (failure == null) message.ack().await() else message.nack(failure).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "SQLite publication callback failed for '$queue'" }
                    } finally {
                        subscription.request(1)
                    }
                }
            }

            override fun onError(t: Throwable) {
                logger.error(t) { "SQLite outgoing channel '$channel' failed" }
            }

            override fun onComplete() = Unit
        }
    }

    @PreDestroy
    fun destroy() = scope.cancel()

    private fun Config.string(key: String): String? = getOptionalValue(key, String::class.java).getOrNull()

    private fun Config.positiveInt(key: String, default: Int): Int =
        (getOptionalValue(key, Int::class.javaObjectType).getOrNull() ?: default).also {
            require(it > 0) { "$key must be positive" }
        }

    companion object {
        const val NAME = "smallrye-sqlite"
    }
}
