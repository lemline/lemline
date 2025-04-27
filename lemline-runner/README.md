# Lemline Worker

This module provides the runtime engine for Lemline, responsible for executing workflows on an underlying
infrastructure. It listens for triggers (e.g., messages), orchestrates task execution according to the workflow
definition, interacts with databases for state persistence, and emits lifecycle events.

## ⚙️ Configuration

The primary configuration for the runner is managed through `src/main/resources/application.properties`. Quarkus
profiles (dev, test, prod) can be used for environment-specific settings.

### Core Configuration Properties

Lemline uses custom properties to easily switch between supported backing services:

* `lemline.database.type`: Set to `postgresql` or `mysql` to select the database.
* `lemline.messaging.type`: Set to `kafka` or `rabbitmq` to select the message broker.

Based on these properties, the runner activates the corresponding Quarkus configuration for datasources and messaging
connectors.

### Database Configuration

The runner requires a database to store workflow state and related data. PostgreSQL and MySQL are currently supported.

**1. Select Database Type:**

Set `lemline.database.type` in `application.properties`:

```properties
# Choose 'postgresql' or 'mysql'
lemline.database.type=postgresql
```

**2. Configure Quarkus Datasource:**

Ensure the corresponding Quarkus datasource properties are configured.

**For PostgreSQL (Default):**

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/lemline_db # Example URL
# Add any other necessary Quarkus datasource properties (pool size, etc.)
```

**For MySQL:**

```properties
quarkus.datasource.db-kind=mysql
quarkus.datasource.username=mysql_user # Example user
quarkus.datasource.password=mysql_pwd # Example password
quarkus.datasource.jdbc.url=jdbc:mysql://localhost:3306/lemline_db # Example URL
# Add any other necessary Quarkus datasource properties
```

**Database Migrations:**

Flyway migrations are located in `src/main/resources/db/migration/`. Database-specific migrations can be placed in
subdirectories (e.g., `postgresql/`, `mysql/`) if needed. Migrations are applied automatically on startup based on the
configured `quarkus.datasource.db-kind`.

### Messaging Configuration

The runner uses a message broker to receive workflow triggers and potentially publish events. Kafka and RabbitMQ are
currently supported.

**1. Select Messaging Type:**

Set `lemline.messaging.type` in `application.properties`:

```properties
# Choose 'kafka' or 'rabbitmq'
lemline.messaging.type=kafka
```

**2. Configure Quarkus Messaging Connector:**

Ensure the corresponding Quarkus messaging properties are configured.

**For Kafka (Default):**

```properties
# --- Core Kafka Connection ---
quarkus.kafka.bootstrap.servers=localhost:9092 # Example: Comma-separated list of brokers
# --- Add other Kafka properties as needed ---
# e.g., security.protocol, sasl.mechanism, etc.
```

**For RabbitMQ:**

```properties
quarkus.rabbitmq.hosts=localhost # Example host
quarkus.rabbitmq.port=5672
quarkus.rabbitmq.username=guest # Example user
quarkus.rabbitmq.password=guest # Example password
# Add any other necessary RabbitMQ properties (vhost, ssl, etc.)
# Configure incoming/outgoing channels (e.g., mp.messaging.incoming.workflows...)
```

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

Remember to align the connection details in `application.properties` with your running Docker containers.

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
java -jar build/quarkus-app/quarkus-run.jar
```

Ensure the environment where you run the JAR has access to the configured database and message broker.

## ✅ Testing

The test suite aims to ensure compatibility with all supported databases and messaging systems.

### Running Tests

Execute the test suite using:

```bash
./gradlew :lemline-runner:test
```

By default, tests might run using a specific configuration profile (often defined in
`src/test/resources/application.properties` or via system properties/environment variables set by the build). Check the
project's test setup for details on how to run tests against different configurations (e.g., MySQL vs PostgreSQL).
Often, this involves activating specific Quarkus test profiles:

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
