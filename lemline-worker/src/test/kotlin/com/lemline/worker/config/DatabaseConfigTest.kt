package com.lemline.worker.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

class DatabaseConfigTest : StringSpec({
    "isPostgreSQL should return true when database type is postgresql" {
        val config = DatabaseConfig()
        config.databaseType = "postgresql"
        config.isPostgreSQL() shouldBe true
    }

    "isPostgreSQL should return true when database type is POSTGRESQL (case insensitive)" {
        val config = DatabaseConfig()
        config.databaseType = "POSTGRESQL"
        config.isPostgreSQL() shouldBe true
    }

    "isPostgreSQL should return false when database type is not postgresql" {
        val config = DatabaseConfig()
        config.databaseType = "mysql"
        config.isPostgreSQL() shouldBe false
    }

    "isMySQL should return true when database type is mysql" {
        val config = DatabaseConfig()
        config.databaseType = "mysql"
        config.isMySQL() shouldBe true
    }

    "isMySQL should return true when database type is MYSQL (case insensitive)" {
        val config = DatabaseConfig()
        config.databaseType = "MYSQL"
        config.isMySQL() shouldBe true
    }

    "isMySQL should return false when database type is not mysql" {
        val config = DatabaseConfig()
        config.databaseType = "postgresql"
        config.isMySQL() shouldBe false
    }
}) 