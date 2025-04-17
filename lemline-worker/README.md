# Lemline Worker

This module contains the core workflow runtime for the Lemline project.

## Database Configuration

The Lemline Worker supports both PostgreSQL (default) and MySQL databases. You can configure the database through the application properties.

### Using PostgreSQL (Default)

PostgreSQL is configured by default. To explicitly use PostgreSQL, ensure the following settings in your `application.properties`:

```properties
# Database selection
lemline.database.type=postgresql

# PostgreSQL configuration
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgres
```

### Using MySQL

To use MySQL instead of PostgreSQL, follow these steps:

1. Start the MySQL container using the provided Docker Compose file:

```bash
docker-compose -f mysql-docker-compose.yaml up -d
```

2. Update your `application.properties` to use MySQL:

```properties
# Database selection
lemline.database.type=mysql

# MySQL configuration
quarkus.datasource.db-kind=mysql
quarkus.datasource.username=mysql
quarkus.datasource.password=mysql
quarkus.datasource.jdbc.url=jdbc:mysql://localhost:3306/lemline
```

3. Comment out the PostgreSQL configuration if it exists:

```properties
# PostgreSQL configuration (disabled)
#quarkus.datasource.db-kind=postgresql
#quarkus.datasource.username=postgres
#quarkus.datasource.password=postgres
#quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgres
```

### Database Migration Structure

The database migrations are organized in database-specific folders:

- `src/main/resources/db/migration/postgresql/` - Contains PostgreSQL-specific migrations
- `src/main/resources/db/migration/mysql/` - Contains MySQL-specific migrations

The appropriate migrations are applied automatically based on the selected database type.

## Messaging Configuration

The Lemline Worker supports Kafka (default) and RabbitMQ for messaging. Configure the broker through the application properties.

### Using Kafka (Default)

Kafka is configured by default.

1. Start the Kafka container using the provided Docker Compose file:

```bash
docker-compose -f kafka-docker-compose.yaml up -d
```

2. To explicitly use Kafka, ensure the following settings in your `application.properties`:

```properties
# Messaging selection
lemline.messaging.type=kafka

# Kafka configuration
quarkus.kafka.bootstrap.servers=localhost:9092

# RabbitMQ configuration (disabled)
# lemaline.messaging.type=rabbitmq
# quarkus.rabbitmq.hosts=localhost
# quarkus.rabbitmq.port=5672
# quarkus.rabbitmq.username=guest
# quarkus.rabbitmq.password=guest
```

### Using RabbitMQ

To use RabbitMQ instead of Kafka:

1. Start the RabbitMQ container using the provided Docker Compose file:

```bash
docker-compose -f rabbitmq-docker-compose.yaml up -d
```

2. Update your `application.properties` to use RabbitMQ:

```properties
# Messaging selection
lemline.messaging.type=rabbitmq

# RabbitMQ configuration
quarkus.rabbitmq.hosts=localhost
quarkus.rabbitmq.port=5672
quarkus.rabbitmq.username=guest
quarkus.rabbitmq.password=guest

# Kafka configuration (disabled)
# lemaline.messaging.type=kafka
# quarkus.kafka.bootstrap.servers=localhost:9092
```

## Running Tests

The tests are organized to run against both PostgreSQL and MySQL. By default, only PostgreSQL tests are run.

### Running PostgreSQL Tests

PostgreSQL tests run by default:

```bash
./gradlew lemline-worker:test
```


## Development Mode

You can run your application in dev mode that enables live coding using:

```bash
./gradlew lemline-worker:quarkusDev
```

## Packaging and Running the Application

The application can be packaged using:

```bash
./gradlew lemline-worker:build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it's not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory. 