---
title: "Tutorial: Setting Up Your Environment"
---

# Tutorial: Setting Up Your Environment

This tutorial walks you through setting up Lemline for local development. By the end, you'll have a fully working environment ready to run workflows.

## Prerequisites

- Docker and Docker Compose installed
- Basic familiarity with command-line terminals
- JDK 17 or higher (only required if using the Java JAR version)

## 1. Start the Infrastructure

This tutorial uses **PostgreSQL** and **Kafka** for local development. You can substitute with other supported databases (MySQL) or message brokers (RabbitMQ, PGMQ). See [Configure Message Brokers](lemline-howto-brokers.md) for alternatives.

Create a project directory and add a `docker-compose.yaml` file:

```bash
mkdir lemline-project
cd lemline-project
```

Create `docker-compose.yaml` with PostgreSQL and Kafka:

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

Start the services:

```bash
docker-compose up -d
```

Verify the services are running:

```bash
docker-compose ps
```

You should see both `postgres` and `kafka` containers running.

## 2. Create the Configuration File

Create a `.lemline.yaml` configuration file in your project directory:

```yaml
lemline:
  database:
    type: postgresql
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

## 3. Download Lemline

Choose your platform and download the appropriate binary:

<tabs group="platform">
<tab id="macos" title="macOS (ARM64)" group-key="macos">

```bash
# Download and extract the native binary
curl -L https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-macos-arm64.tar.gz | tar -xz

# Verify the installation
bin/lemline --version
```

</tab>
<tab id="linux" title="Linux (x86_64)" group-key="linux">

```bash
# Download and extract the native binary
curl -L https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-linux-x86_64.tar.gz | tar -xz

# Verify the installation
bin/lemline --version
```

</tab>
<tab id="windows" title="Windows (x86_64)" group-key="windows">

```powershell
# Download and extract the native binary
Invoke-WebRequest -Uri "https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-windows-x86_64.zip" -OutFile "lemline.zip"
Expand-Archive -Path "lemline.zip" -DestinationPath "."
Remove-Item "lemline.zip"

# Verify the installation
bin\lemline.exe --version
```

</tab>
<tab id="java" title="Java (Any OS)" group-key="java">

Requires JDK 17 or higher.

```bash
# Download the JAR
curl -L https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%.jar -o lemline.jar

# Verify the installation
java -jar lemline.jar --version
```

> **Tip**: The Java JAR version supports in-memory mode for quick experimentation without Docker. See [Alternative: In-Memory Mode](#alternative-in-memory-mode-java-only) below.

</tab>
</tabs>

You should see output showing the Lemline version (e.g., `%version%`).

## 4. Verify the Setup

Test that Lemline can connect to your infrastructure:

<tabs group="platform">
<tab id="macos-verify" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline config
```

</tab>
<tab id="linux-verify" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline config
```

</tab>
<tab id="windows-verify" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe config
```

</tab>
<tab id="java-verify" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar config
```

</tab>
</tabs>

This displays the resolved configuration. Verify the database and messaging settings match your `docker-compose.yaml`.

## Your Environment is Ready!

You now have:
- PostgreSQL database for workflow state persistence
- Kafka message broker for workflow execution
- Lemline binary configured to use both services

## Next Steps

Continue with the [Hello, Workflow!](lemline-tutorial-hello.md) tutorial to create and run your first workflow.

---

## Alternative: In-Memory Mode (Java Only)

If you want to quickly experiment without Docker, the Java JAR version supports in-memory mode:

Create a minimal `.lemline.yaml`:

```yaml
lemline:
  database:
    type: in-memory
  messaging:
    type: in-memory
```

Then run workflows directly:

```bash
java -jar lemline.jar workflow run your-workflow.yaml
```

> **Note**: In-memory mode is only available with the Java JAR. Native binaries require PostgreSQL.

