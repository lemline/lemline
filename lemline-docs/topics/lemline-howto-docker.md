---
title: "How to Set Up with Docker"
---

# How to Set Up with Docker

This guide shows how to set up a local development environment with Docker Compose using PostgreSQL and a message broker.

> **Note**: For quick experimentation and tutorials, use the [in-memory configuration](lemline-tutorial-hello.md) instead. For production deployments, use managed services (e.g., AWS RDS, Amazon MSK, CloudAMQP) or your organization's infrastructure.

## Prerequisites

- Docker and Docker Compose installed
- Basic familiarity with Docker concepts

## Quick Start

This guide uses **PostgreSQL** and **Kafka**. You can substitute with other supported databases (MySQL) or message brokers (RabbitMQ, PGMQ). See [Configure Message Brokers](lemline-howto-brokers.md) for alternatives.

Create a `docker-compose.yaml` file:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: lemline
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"

  kafka:
    image: apache/kafka:latest
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: lemline-local-cluster
    ports:
      - "9092:9092"
```

Create a `.lemline.yaml` configuration file:

```yaml
lemline:
  database:
    type: postgresql
    migrate-at-start: true
    postgresql:
      host: localhost
      port: 5432
      name: lemline
      username: postgres
      password: postgres

  messaging:
    type: kafka
    kafka:
      brokers: localhost:9092
      commands:
        topic: lemline-commands
      events:
        topic: lemline-events
```

Start the infrastructure:

```bash
docker-compose up -d
```

Run Lemline:

```bash
bin/lemline listen --info
```

## Using RabbitMQ Instead of Kafka

If you prefer RabbitMQ, replace the Kafka service in `docker-compose.yaml`:

```yaml
services:
  postgres:
    # ... same as above ...

  rabbitmq:
    image: rabbitmq:3-management
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    ports:
      - "5672:5672"
      - "15672:15672"  # Management UI
```

And update `.lemline.yaml`:

```yaml
lemline:
  database:
    type: postgresql
    migrate-at-start: true
    postgresql:
      host: localhost
      port: 5432
      name: lemline
      username: postgres
      password: postgres

  messaging:
    type: rabbitmq
    rabbitmq:
      hostname: localhost
      port: 5672
      username: guest
      password: guest
      commands:
        queue: lemline-commands
      events:
        queue: lemline-events
```

## Next Steps

- [Configure message brokers](lemline-howto-brokers.md) for advanced broker settings
- [Scale Lemline workers](lemline-howto-scaling.md) for high-throughput workloads
- [Set up observability](lemline-howto-observability.md) for monitoring and metrics
