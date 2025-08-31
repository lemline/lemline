//package com.lemline.runner.ingestion
//
//import com.lemline.runner.config.CONSUMER_ENABLED
//import com.lemline.runner.config.MESSAGING_CONSUMER_CONCURRENCY
//import com.lemline.runner.instances.InstanceMessageHandler
//import com.lemline.runner.messaging.MessageSubscriber
//import io.quarkus.runtime.Startup
//import jakarta.enterprise.context.ApplicationScoped
//import kotlin.time.ExperimentalTime
//import org.eclipse.microprofile.config.inject.ConfigProperty
//import org.eclipse.microprofile.reactive.messaging.Channel
//import org.eclipse.microprofile.reactive.messaging.Message
//import org.reactivestreams.Publisher
//
//internal const val INGESTION_IN = "ingestion-in"
//
//@OptIn(ExperimentalTime::class)
//@Startup
//@ApplicationScoped
//internal class IngestionMessageSubscriber(
//    @param:ConfigProperty(name = MESSAGING_CONSUMER_CONCURRENCY) override val maxConcurrency: Int,
//    @param:ConfigProperty(name = CONSUMER_ENABLED) override val enabled: Boolean,
//    @param:Channel(INGESTION_IN) override val publisher: Publisher<Message<String>>,
//    override val handler: InstanceMessageHandler,
//    override val metrics: IngestionMessageSubscriberMetrics,
//) : MessageSubscriber()
