# Lemline Worker

This module provides the runtime engine for Lemline, responsible for executing workflows on an underlying
infrastructure. It listens for triggers (e.g., messages), orchestrates task execution according to the workflow
definition, interacts with databases for state persistence, and emits lifecycle events.

## ⚙️ Configuration

### Getting Started with Configuration

An example configuration file (`application.yaml.example`) is provided to help you get started. This file contains:

1. A complete example configuration with all available options
2. Detailed comments explaining each setting
3. Environment variable placeholders for sensitive values
4. Example profiles for different environments

To use it:

1. Copy `application.yaml.example` to your desired location
2. Rename it to `application.yaml`
3. Configure it according to your environment
4. Use it when running the runner (see "Running the Worker" section below)

The example file includes:

- Database configuration for PostgreSQL and MySQL
- Messaging configuration for Kafka and RabbitMQ
- Service settings for wait and retry operations
- Profile-specific configurations
- Security best practices and recommendations

The runner can be configured using a YAML configuration file. You can provide your configuration in one of these ways:

Create an `application.yaml` file in the same directory as the Lemline binary, or use the `-Dquarkus.config.locations`
system property to specify the path to your configuration file

Quarkus profiles (dev, test, prod) can be used for environment-specific settings.

In the JVM:

```bash
QUARKUS_CONFIG_LOCATIONS=application.yml java  -jar lemline-runner/build/quarkus-app/quarkus-run.jar
```

With the native runner:

```bash
QUARKUS_CONFIG_LOCATIONS=application.yml  ./lemline-runner/build/lemline-runner-0.0.1-SNAPSHOT-runner 
```

### Core Configuration Properties

Lemline uses custom properties to easily switch between supported backing services:

* `lemline.database.type`: Set to `postgresql` or `mysql` to select the database.
* `lemline.messaging.type`: Set to `kafka` or `rabbitmq` to select the message broker.

Based on these properties, the runner activates the corresponding Quarkus configuration for datasources and messaging
connectors.

### Database Configuration

The runner requires a database to store workflow state and related data. PostgreSQL and MySQL are currently supported.

**1. Select Database Type:**

Set `lemline.database.type` in your configuration file:

```yaml
lemline:
    database:
        type: ${LEMLINE_DB_TYPE:postgresql}  # Can be overridden by LEMLINE_DB_TYPE env var
```

**2. Configure Database Connection:**

**For PostgreSQL (Default):**

```yaml
lemline:
    database:
        type: postgresql
        postgresql:
            host: ${LEMLINE_PG_HOST:localhost}
            port: ${LEMLINE_PG_PORT:5432}
            username: ${LEMLINE_PG_USER:postgres}
            password: ${LEMLINE_PG_PASSWORD:postgres}  # RECOMMENDED: Set via LEMLINE_PG_PASSWORD env var!
            name: ${LEMLINE_PG_DB_NAME:lemline}
```

**For MySQL:**

```yaml
lemline:
    database:
        type: mysql
        mysql:
            host: ${LEMLINE_MYSQL_HOST:localhost}
            port: ${LEMLINE_MYSQL_PORT:3306}
            username: ${LEMLINE_MYSQL_USER:root}
            password: ${LEMLINE_MYSQL_PASSWORD:password}  # RECOMMENDED: Set via LEMLINE_MYSQL_PASSWORD env var!
            name: ${LEMLINE_MYSQL_DB_NAME:lemline}
```

**Database Migrations:**

Flyway migrations are included in the application and can be applied automatically on startup. By default, migrations
are not applied. To enable automatic migration, add these settings to your configuration:

```yaml
lemline:
    database:
        # Create the schema history table if it doesn't exist
        baseline-on-migrate: true

        # Apply database migrations at startup
        migrate-at-start: true
```

When enabled, migrations will be applied based on the configured database type. The migrations are versioned and will be
applied in order, ensuring your database schema is up to date.

### Messaging Configuration

The runner uses a message broker to receive workflow triggers and potentially publish events.
Kafka and RabbitMQ are currently supported.

**1. Select Messaging Type:**

Set `lemline.messaging.type` in your configuration file:

```yaml
lemline:
    messaging:
        type: ${LEMLINE_MESSAGING_TYPE:kafka}
```

**2. Configure Message Broker:**

**For Kafka (Default):**

```yaml
lemline:
    messaging:
        type: kafka
        kafka:
            brokers: ${LEMLINE_KAFKA_BROKERS:localhost:9092}
            topic: ${LEMLINE_KAFKA_TOPIC:lemline}
            topic-dlq: ${LEMLINE_KAFKA_TOPIC_DLQ:lemline-dlq}
            group-id: ${LEMLINE_KAFKA_GROUP_ID:lemline-worker-group}
            # Optional security settings:
            # security-protocol: SASL_SSL
            # sasl-mechanism: PLAIN
            # sasl-username: ${LEMLINE_KAFKA_USER}
            # sasl-password: ${LEMLINE_KAFKA_PASSWORD}
```

**For RabbitMQ:**

```yaml
lemline:
    messaging:
        type: rabbitmq
        rabbitmq:
            hostname: ${LEMLINE_RABBITMQ_HOST:localhost}
            port: ${LEMLINE_RABBITMQ_PORT:5672}
            username: ${LEMLINE_RABBITMQ_USER:guest}
            password: ${LEMLINE_RABBITMQ_PASSWORD:guest}  # RECOMMENDED: Set via LEMLINE_RABBITMQ_PASSWORD env var!
            virtual-host: ${LEMLINE_RABBITMQ_VHOST:/}
            queue: ${LEMLINE_RABBITMQ_QUEUE_IN:workflows}
```

### Service Settings

The runner includes configurable service settings for task scheduling and cleanup:

```yaml
lemline:
    # Wait Service Settings
    wait:
        outbox:
            every: "10s"
            batch-size: 1000
            initial-delay: "30s"
            max-attempts: 5
        cleanup:
            every: "1h"
            after: "7d"
            batch-size: 1000

    # Retry Service Settings
    retry:
        outbox:
            every: "10s"
            batch-size: 1000
            initial-delay: "30s"
            max-attempts: 5
        cleanup:
            every: "1h"
            after: "7d"
            batch-size: 1000
```

### Profile-Specific Configuration

You can override settings for specific Quarkus profiles (e.g., prod, dev, test) by adding profile-specific sections:

```yaml
'%prod':
    quarkus:
        log:
            level: WARN
            category:
                "com.lemline":
                    level: INFO

    lemline:
        database:
            type: postgresql
            postgresql:
                host: prod-db.example.com
                username: ${LEMLINE_PROD_PG_USER}
                password: ${LEMLINE_PROD_PG_PASSWORD}
                name: lemline_production_db
```

Activate a profile by starting Lemline with the system property: `-Dquarkus.profile=prod`

### Local Development Setup (Docker Examples)

For local development, you can use Docker Compose to quickly start database and message broker instances. Example files
might be provided in the project root or `/docker` directory (e.g., `docker-compose-postgres.yaml`,
`docker-compose-kafka.yaml`).

*Example commands (adjust paths as needed):*

```bash
# Start PostgreSQL
docker-compose -f ./docker/docker-compose-postgres.yaml up -d

# Start Kafka
docker-compose -f ./docker/docker-compose-kafka.yaml up -d
```

Remember to align the connection details in your configuration file with your running Docker containers.

## ▶️ Running the Worker

### Development Mode

Run the runner in Quarkus dev mode for live coding and quick feedback:

```bash
./gradlew :lemline-runner:quarkusDev
```

The runner will start, connect to the configured database and message broker, apply migrations, and begin listening for
workflow triggers.

### Packaging and Running the Application

Build the application into a runnable JAR:

```bash
./gradlew :lemline-runner:build
```

This produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory. Run it using:

```bash
# Run with default configuration
java -jar build/quarkus-app/quarkus-run.jar

# Run with custom configuration file
java -Dquarkus.config.locations=/path/to/your/application.yaml -jar build/quarkus-app/quarkus-run.jar

# Run with specific profile
java -Dquarkus.profile=prod -jar build/quarkus-app/quarkus-run.jar
```

Ensure the environment where you run the JAR has access to the configured database and message broker.

## ✅ Testing

The test suite aims to ensure compatibility with all supported databases and messaging systems.

### Running Tests

Execute the test suite using:

```bash
./gradlew :lemline-runner:test
```

By default, tests run using a specific configuration profile defined in the test resources. You can run tests against
different configurations by activating specific Quarkus test profiles:

```bash
# Example: Running tests with a specific profile (adjust profile name)
./gradlew :lemline-runner:test -Dquarkus.test.profile=mysql-test
```

Refer to the `build.gradle.kts` and test configuration files for the exact profile names and setup.

## 🔗 Dependencies

* **`lemline-core`:** Provides the Serverless Workflow DSL models and core logic used by the runner.
* **Quarkus:** The runtime framework.
* **Hibernate/Panache:** For database interaction.
* **SmallRye Reactive Messaging:** For Kafka/RabbitMQ integration.
* **Flyway:** For database migrations.
* **Kotlin Coroutines:** For asynchronous operations. 
