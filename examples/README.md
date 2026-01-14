# Lemline Example Configurations

This directory contains Docker Compose and configuration files to test Lemline with different messaging broker and database combinations.

## Available Configurations

| Configuration | Messaging | Database | Profile | Config File |
|---------------|-----------|----------|---------|-------------|
| Kafka + PostgreSQL | Kafka | PostgreSQL | `kafka-pg` | `lemline-kafka-pg.yaml` |
| Kafka + MySQL | Kafka | MySQL | `kafka-mysql` | `lemline-kafka-mysql.yaml` |
| RabbitMQ + PostgreSQL | RabbitMQ | PostgreSQL | `rabbit-pg` | `lemline-rabbit-pg.yaml` |
| RabbitMQ + MySQL | RabbitMQ | MySQL | `rabbit-mysql` | `lemline-rabbit-mysql.yaml` |
| PGMQ + PostgreSQL | PGMQ | PostgreSQL | `pgmq` | `lemline-pgmq.yaml` |

## Quick Start

### 1. Start Infrastructure

Choose a profile and start the required services:

```bash
# Kafka + PostgreSQL
docker compose --profile kafka-pg up -d

# Kafka + MySQL
docker compose --profile kafka-mysql up -d

# RabbitMQ + PostgreSQL
docker compose --profile rabbit-pg up -d

# RabbitMQ + MySQL
docker compose --profile rabbit-mysql up -d

# PGMQ + PostgreSQL (uses PostgreSQL for both DB and messaging)
docker compose --profile pgmq up -d

# Start ALL services (for switching between configurations)
docker compose --profile all up -d
```

### 2. Wait for Services to be Ready

```bash
# Check service health
docker compose ps
```

### 3. Run Lemline

```bash
# From the project root directory
cd ..

# Build if needed
./gradlew :lemline-runner:build -x test

# Run with desired configuration
LEMLINE_CONFIG=./examples/lemline-kafka-pg.yaml \
  java -jar lemline-runner/build/quarkus-app/quarkus-run.jar listen
```

### 4. Stop Infrastructure

```bash
# Stop specific profile
docker compose --profile kafka-pg down

# Stop and remove volumes (clean slate)
docker compose --profile kafka-pg down -v
```

## Service Details

### Ports

| Service | Port | Description |
|---------|------|-------------|
| PostgreSQL | 5432 | Database |
| MySQL | 3306 | Database |
| Kafka | 9092 | Message broker |
| Kafka UI | 8080 | Web UI for Kafka |
| Zookeeper | 2181 | Kafka coordination |
| RabbitMQ | 5672 | Message broker (AMQP) |
| RabbitMQ UI | 15672 | Management UI |

### Default Credentials

**PostgreSQL:**
- Username: `postgres`
- Password: `postgres`
- Database: `lemline`

**MySQL:**
- Username: `lemline`
- Password: `lemline`
- Database: `lemline`
- Root password: `root`

**Kafka:**
- No authentication (development mode)

**RabbitMQ:**
- Username: `guest`
- Password: `guest`

## Customization

### Environment Variables

You can override configuration values using environment variables:

```bash
# Override database password
LEMLINE_DATABASE_POSTGRESQL_PASSWORD=secret \
  LEMLINE_CONFIG=./examples/lemline-kafka-pg.yaml \
  java -jar lemline-runner.jar listen
```

### Multiple Workers

To test horizontal scaling, run multiple Lemline instances with the same configuration:

```bash
# Terminal 1
LEMLINE_CONFIG=./examples/lemline-kafka-pg.yaml java -jar lemline-runner.jar listen

# Terminal 2
LEMLINE_CONFIG=./examples/lemline-kafka-pg.yaml java -jar lemline-runner.jar listen
```

### Custom Topics/Queues

Edit the configuration files to customize topic/queue names:

```yaml
lemline:
  messaging:
    kafka:
      commands:
        topic: my-custom-commands-topic
      events:
        topic: my-custom-events-topic
```

## Troubleshooting

### Services Won't Start

Check Docker resources and logs:

```bash
docker compose logs postgres
docker compose logs kafka
docker compose logs rabbitmq
```

### Connection Refused

Ensure services are healthy before starting Lemline:

```bash
docker compose ps  # All services should show "healthy"
```

### Kafka Topics Not Created

Kafka auto-creates topics on first use. If you need to pre-create topics:

```bash
docker exec lemline-kafka kafka-topics \
  --create --topic lemline-commands \
  --bootstrap-server localhost:9092 \
  --partitions 4 \
  --replication-factor 1
```

