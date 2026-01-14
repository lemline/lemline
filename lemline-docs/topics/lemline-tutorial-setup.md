---
title: Setting Up A Local Environment
---

# Setting Up A Local Environment

This tutorial walks you through setting up Lemline for local development. By the end, you'll have a fully working environment ready to run workflows.

## Prerequisites

- Docker and Docker Compose installed
- Basic familiarity with command-line terminals
- JDK 17 or higher (only required if using the Java JAR version)

## 1. Start the Infrastructure

This tutorial uses **PostgreSQL** with **PGMQ** (PostgreSQL Message Queue) for local development. PGMQ runs in SQL-only mode, requiring no extensions—just a standard PostgreSQL instance handles both data persistence and message brokering. You can substitute with other supported databases (MySQL) or message brokers (Kafka, RabbitMQ) that mirror your production infrastructure. See [Configure Message Brokers](lemline-howto-brokers.md) for alternatives.

Create a project directory and add a `docker-compose.yaml` file:

```bash
mkdir lemline-project
cd lemline-project
```

Create `docker-compose.yaml` with PostgreSQL:

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
```

Start the service:

```bash
docker-compose up -d
```

Verify the service is running:

```bash
docker-compose ps
```

You should see the `postgres` container running.

## 2. Create the Configuration File

Create a `.lemline.yaml` configuration file in your project directory:

```yaml
lemline:
  database:
    postgresql:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
      password: postgres

  messaging:
    pgmq:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
      password: postgres
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


</tab>
</tabs>

You should see output showing the Lemline version (e.g., `%version%`).

## 4. Run Database Migrations

Lemline requires database tables for workflow state persistence. Run the migration command to create them:

<tabs group="platform">
<tab id="macos-migrate" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline migrate
```

</tab>
<tab id="linux-migrate" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline migrate
```

</tab>
<tab id="windows-migrate" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe migrate
```

</tab>
<tab id="java-migrate" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar migrate
```

</tab>
</tabs>

You should see output indicating successful migration. This creates the necessary tables for workflow definitions, instances, retries, waits, and other operational data—as well as the PGMQ schema and functions for message brokering. The actual message queue tables are created automatically when Lemline first starts.

## Your Environment is Ready!

You now have:
- PostgreSQL database for workflow state persistence
- PGMQ message broker for workflow execution (built into PostgreSQL)
- Lemline binary configured and ready to run workflows

## Next Steps

Continue with the [Hello, Workflow!](lemline-tutorial-hello.md) tutorial to create and run your first workflow.
