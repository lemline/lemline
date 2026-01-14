// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.util.*
import org.eclipse.microprofile.config.Config

class PgmqConfigurationTest : FunSpec({

    context("PgmqConnectorConfig constants") {
        test("should have correct connector name") {
            PgmqConnectorConfig.CONNECTOR_NAME shouldBe "smallrye-pgmq"
        }

        test("should have correct default host") {
            PgmqConnectorConfig.DEFAULT_HOST shouldBe "localhost"
        }

        test("should have correct default port") {
            PgmqConnectorConfig.DEFAULT_PORT shouldBe 5432
        }

        test("should have correct default database") {
            PgmqConnectorConfig.DEFAULT_DATABASE shouldBe "lemline"
        }

        test("should have correct default visibility timeout") {
            PgmqConnectorConfig.DEFAULT_VISIBILITY_TIMEOUT shouldBe 30
        }

        test("should have correct default poll interval") {
            PgmqConnectorConfig.DEFAULT_POLL_INTERVAL shouldBe 100L
        }

        test("should have correct default batch size") {
            PgmqConnectorConfig.DEFAULT_BATCH_SIZE shouldBe 10
        }

        test("should have correct default max pool size") {
            PgmqConnectorConfig.DEFAULT_MAX_POOL_SIZE shouldBe 5
        }

        test("should have correct default max retries") {
            PgmqConnectorConfig.DEFAULT_MAX_RETRIES shouldBe 3
        }
    }

    context("PgmqConnectorConfig property retrieval") {
        test("should read from incoming channel first") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test-channel.host",
                    String::class.java
                )
            } returns Optional.of("incoming-host")
            every {
                config.getOptionalValue(
                    "mp.messaging.outgoing.test-channel.host",
                    String::class.java
                )
            } returns Optional.of("outgoing-host")

            val connectorConfig = PgmqConnectorConfig(config, "test-channel")

            connectorConfig.host shouldBe "incoming-host"
        }

        test("should fallback to outgoing channel when incoming not found") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.test-channel.host",
                    String::class.java
                )
            } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.outgoing.test-channel.host",
                    String::class.java
                )
            } returns Optional.of("outgoing-host")

            val connectorConfig = PgmqConnectorConfig(config, "test-channel")

            connectorConfig.host shouldBe "outgoing-host"
        }

        test("should use default when neither incoming nor outgoing found") {
            val config = mockk<Config>()
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "test-channel")

            connectorConfig.host shouldBe "localhost"
        }
    }

    context("PgmqConnectorConfig full configuration") {
        fun createFullyConfiguredConfig(): PgmqConnectorConfig {
            val config = mockk<Config>()
            val channel = "full-channel"

            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.host",
                    String::class.java
                )
            } returns Optional.of("pg.example.com")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.port",
                    String::class.java
                )
            } returns Optional.of("5433")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.database",
                    String::class.java
                )
            } returns Optional.of("mydb")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.username",
                    String::class.java
                )
            } returns Optional.of("admin")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.password",
                    String::class.java
                )
            } returns Optional.of("secret123")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.queue",
                    String::class.java
                )
            } returns Optional.of("my-queue")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.visibility-timeout",
                    String::class.java
                )
            } returns Optional.of("60")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.poll-interval",
                    String::class.java
                )
            } returns Optional.of("200")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.batch-size",
                    String::class.java
                )
            } returns Optional.of("25")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.max-pool-size",
                    String::class.java
                )
            } returns Optional.of("10")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.auto-create-queue",
                    String::class.java
                )
            } returns Optional.of("false")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.dead-letter-queue",
                    String::class.java
                )
            } returns Optional.of("my-dlq")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.$channel.max-retries",
                    String::class.java
                )
            } returns Optional.of("5")
            every {
                config.getOptionalValue(
                    match { !it.startsWith("mp.messaging.incoming.$channel.") },
                    String::class.java
                )
            } returns Optional.empty()

            return PgmqConnectorConfig(config, channel)
        }

        test("should read all configured values correctly") {
            val connectorConfig = createFullyConfiguredConfig()

            connectorConfig.host shouldBe "pg.example.com"
            connectorConfig.port shouldBe 5433
            connectorConfig.database shouldBe "mydb"
            connectorConfig.username shouldBe "admin"
            connectorConfig.password shouldBe "secret123"
            connectorConfig.queue shouldBe "my-queue"
            connectorConfig.visibilityTimeout shouldBe 60
            connectorConfig.batchSize shouldBe 25
            connectorConfig.maxPoolSize shouldBe 10
            connectorConfig.autoCreateQueue shouldBe false
            connectorConfig.deadLetterQueue shouldBe "my-dlq"
            connectorConfig.maxRetries shouldBe 5
        }

        test("should generate correct poll interval duration") {
            val connectorConfig = createFullyConfiguredConfig()

            connectorConfig.pollInterval shouldBe Duration.ofMillis(200)
        }
    }

    context("PgmqConnectorConfig JDBC URL generation") {
        test("should generate standard JDBC URL") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.host",
                    String::class.java
                )
            } returns Optional.of("db.example.com")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.port",
                    String::class.java
                )
            } returns Optional.of("5432")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.database",
                    String::class.java
                )
            } returns Optional.of("testdb")

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            connectorConfig.toJdbcUrl() shouldBe "jdbc:postgresql://db.example.com:5432/testdb"
        }

        test("should generate JDBC URL with custom port") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.host",
                    String::class.java
                )
            } returns Optional.of("localhost")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.port",
                    String::class.java
                )
            } returns Optional.of("15432")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.database",
                    String::class.java
                )
            } returns Optional.of("lemline")

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            connectorConfig.toJdbcUrl() shouldBe "jdbc:postgresql://localhost:15432/lemline"
        }
    }

    context("PgmqConnectorConfig connection options") {
        test("should generate connection options map") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.host",
                    String::class.java
                )
            } returns Optional.of("myhost")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.port",
                    String::class.java
                )
            } returns Optional.of("5433")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.database",
                    String::class.java
                )
            } returns Optional.of("mydb")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.username",
                    String::class.java
                )
            } returns Optional.of("myuser")
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.password",
                    String::class.java
                )
            } returns Optional.of("mypass")

            val connectorConfig = PgmqConnectorConfig(config, "ch")
            val options = connectorConfig.toConnectionOptions()

            options["host"] shouldBe "myhost"
            options["port"] shouldBe 5433
            options["database"] shouldBe "mydb"
            options["user"] shouldBe "myuser"
            options["password"] shouldBe "mypass"
        }
    }

    context("Edge cases") {
        test("should handle channel name with special characters") {
            val config = mockk<Config>()
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "commands-in")

            connectorConfig.queue shouldBe "commands-in"
        }

        test("should handle empty password") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.password",
                    String::class.java
                )
            } returns Optional.of("")

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            connectorConfig.password shouldBe ""
        }

        test("should return null for unconfigured dead letter queue") {
            val config = mockk<Config>()
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            connectorConfig.deadLetterQueue shouldBe null
        }

        test("should handle zero poll interval") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.poll-interval",
                    String::class.java
                )
            } returns Optional.of("0")

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            connectorConfig.pollInterval shouldBe Duration.ZERO
        }

        test("should handle negative values gracefully") {
            val config = mockk<Config>()
            // Order matters: any() first, specific matchers after to override
            every { config.getOptionalValue(any(), String::class.java) } returns Optional.empty()
            every {
                config.getOptionalValue(
                    "mp.messaging.incoming.ch.batch-size",
                    String::class.java
                )
            } returns Optional.of("-5")

            val connectorConfig = PgmqConnectorConfig(config, "ch")

            // Negative value is parsed, validation should be done at usage
            connectorConfig.batchSize shouldBe -5
        }
    }
})
