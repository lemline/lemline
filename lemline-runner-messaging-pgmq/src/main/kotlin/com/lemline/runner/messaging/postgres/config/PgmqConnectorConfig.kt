// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.config

import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import org.eclipse.microprofile.config.Config
import java.time.Duration

/**
 * Configuration for the PGMQ SmallRye Reactive Messaging connector.
 *
 * Configuration properties:
 * - host: PostgreSQL host (default: localhost)
 * - port: PostgreSQL port (default: 5432)
 * - database: Database name (default: lemline)
 * - username: Database username (default: postgres)
 * - password: Database password (default: postgres)
 * - queue: PGMQ queue name (required)
 * - visibility-timeout: Message visibility timeout in seconds (default: 30)
 * - poll-interval: Polling interval in milliseconds (default: 100)
 * - batch-size: Number of messages to fetch per poll (default: 10)
 * - max-pool-size: Connection pool size (default: 5)
 */
class PgmqConnectorConfig(
    private val config: Config,
    private val channelName: String,
) {
    companion object {
        const val CONNECTOR_NAME = "smallrye-pgmq"

        // Configuration property names
        const val HOST = "host"
        const val PORT = "port"
        const val DATABASE = "database"
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val QUEUE = "queue"
        const val VISIBILITY_TIMEOUT = "visibility-timeout"
        const val POLL_INTERVAL = "poll-interval"
        const val BATCH_SIZE = "batch-size"
        const val MAX_POOL_SIZE = "max-pool-size"
        const val AUTO_CREATE_QUEUE = "auto-create-queue"
        const val DEAD_LETTER_QUEUE = "dead-letter-queue"
        const val MAX_RETRIES = "max-retries"

        // Default values
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 5432
        const val DEFAULT_DATABASE = "lemline"
        const val DEFAULT_USERNAME = "postgres"
        const val DEFAULT_PASSWORD = "postgres"
        const val DEFAULT_VISIBILITY_TIMEOUT = 30
        const val DEFAULT_POLL_INTERVAL = 100L
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_MAX_POOL_SIZE = 5
        const val DEFAULT_AUTO_CREATE_QUEUE = true
        const val DEFAULT_MAX_RETRIES = 3
    }

    private fun getProperty(key: String): String? {
        // Check channel-specific properties first (incoming, then outgoing)
        return config.getOptionalValue("mp.messaging.incoming.$channelName.$key", String::class.java)
            .orElseGet {
                config.getOptionalValue("mp.messaging.outgoing.$channelName.$key", String::class.java)
                    .orElseGet {
                        // Fall back to global connector defaults (pgmq.host, pgmq.port, etc.)
                        config.getOptionalValue("pgmq.$key", String::class.java).orElse(null)
                    }
            }
    }

    val host: String
        get() = getProperty(HOST) ?: DEFAULT_HOST

    val port: Int
        get() = getProperty(PORT)?.toIntOrNull() ?: DEFAULT_PORT

    val database: String
        get() = getProperty(DATABASE) ?: DEFAULT_DATABASE

    val username: String
        get() = getProperty(USERNAME) ?: DEFAULT_USERNAME

    val password: String
        get() = getProperty(PASSWORD) ?: DEFAULT_PASSWORD

    val queue: String
        get() = getProperty(QUEUE) ?: channelName

    val visibilityTimeout: Int
        get() = getProperty(VISIBILITY_TIMEOUT)?.toIntOrNull() ?: DEFAULT_VISIBILITY_TIMEOUT

    val pollInterval: Duration
        get() = Duration.ofMillis(getProperty(POLL_INTERVAL)?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL)

    val batchSize: Int
        get() = getProperty(BATCH_SIZE)?.toIntOrNull() ?: DEFAULT_BATCH_SIZE

    val maxPoolSize: Int
        get() = getProperty(MAX_POOL_SIZE)?.toIntOrNull() ?: DEFAULT_MAX_POOL_SIZE

    val autoCreateQueue: Boolean
        get() = getProperty(AUTO_CREATE_QUEUE)?.toBooleanStrictOrNull() ?: DEFAULT_AUTO_CREATE_QUEUE

    val deadLetterQueue: String?
        get() = getProperty(DEAD_LETTER_QUEUE)

    val maxRetries: Int
        get() = getProperty(MAX_RETRIES)?.toIntOrNull() ?: DEFAULT_MAX_RETRIES

    /**
     * Returns the JDBC connection string for this configuration.
     */
    fun toJdbcUrl(): String = "jdbc:postgresql://$host:$port/$database"

    /**
     * Returns a connection options map for Vert.x PgPool.
     */
    fun toConnectionOptions(): Map<String, Any> = mapOf(
        "host" to host,
        "port" to port,
        "database" to database,
        "user" to username,
        "password" to password,
    )
}
