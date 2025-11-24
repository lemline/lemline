# Lemline Runner Documentation

The `lemline-runner` module is the Quarkus-based runtime that provides messaging, persistence, scheduling, and CLI for workflow execution.

## Documentation Index

| Document | Description | When to Read |
|----------|-------------|--------------|
| [runner-configuration.md](runner-configuration.md) | Configuration system, file search order, database/messaging setup | Setting up Lemline |
| [runner-messaging.md](runner-messaging.md) | Dual-channel design, commands/events, message flow | Understanding messaging |
| [runner-tables.md](runner-tables.md) | Database tables, outbox pattern, schemas | Working with persistence |
| [runner-repositories-guide.md](runner-repositories-guide.md) | Repository patterns, transactions, migrations | Implementing repositories |
| [runner-logging.md](runner-logging.md) | Logging strategy, MDC context, configuration | Debugging and monitoring |
| [runner-cli.md](runner-cli.md) | CLI commands, usage examples | Operating Lemline |

## Quick Reference

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `StepByStepRunner` | `runner/` | Workflow execution orchestrator |
| `WorkflowCommandHandler` | `messaging/commands/` | Process workflow commands |
| `WorkflowEventHandler` | `messaging/events/` | Handle workflow events |
| `AbstractOutbox` | `outbox/` | Base outbox processor |
| `Repository<T>` | `repositories/` | Base repository class |
| `DatabaseManager` | `config/` | Database connection management |

### Common Tasks

| Task | Documentation |
|------|---------------|
| Configure database | [runner-configuration.md](runner-configuration.md#database-configuration) |
| Add message broker | [runner-messaging.md](runner-messaging.md#adding-a-new-event-type) |
| Create new table | [runner-tables.md](runner-tables.md#adding-a-new-table) |
| Implement repository | [runner-repositories-guide.md](runner-repositories-guide.md#repository-base-classes) |
| Configure logging | [runner-logging.md](runner-logging.md#configuration) |
| Run CLI commands | [runner-cli.md](runner-cli.md) |

## Architecture Overview

```
lemline-runner/
├── cli/                    # Picocli commands
├── config/                 # Configuration and database management
├── messaging/
│   ├── commands/          # WorkflowCommand handling
│   └── events/            # WorkflowEvent handling
├── models/                 # Database entity models
├── outbox/                 # Outbox pattern processors
├── repositories/           # Database repositories
└── StepByStepRunner.kt    # Main execution orchestrator
```

## Commands

```bash
# Start listener (consumes workflow messages)
./gradlew :lemline-runner:quarkusDev

# Run tests
./gradlew :lemline-runner:test

# Run specific test
./gradlew :lemline-runner:test --tests "YourTestClass"

# Build JAR
./gradlew :lemline-runner:build

# Build native image
./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true
```
