# lemline-runner-cli

> Command-line interface for Lemline workflow runtime

## Purpose

This module provides the Picocli-based CLI for interacting with the Lemline runtime:
- **listen** - Start the workflow message consumer
- **config** - Display runtime configuration
- **definition** - Manage workflow definitions (CRUD)
- **instance** - Start workflow instances
- **migrate** - Run database migrations

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      lemline-runner-cli                         │
├─────────────────────────────────────────────────────────────────┤
│  MainCommand                ← @TopCommand entry point           │
│  ├── ListenCommand          ← Start message consumer            │
│  ├── ConfigCommand          ← Display configuration             │
│  ├── MigrateCommand         ← Database migrations               │
│  │   └── MigrateStatusCommand                                   │
│  ├── DefinitionCommand      ← Workflow definition management    │
│  │   ├── DefinitionGetCommand                                   │
│  │   ├── DefinitionPostCommand                                  │
│  │   └── DefinitionDeleteCommand                                │
│  └── InstanceCommand        ← Workflow instance management      │
│       └── InstanceStartCommand                                  │
│                                                                 │
│  GlobalMixin                ← Shared options (--config, etc.)   │
│  VersionProvider            ← Version from manifest             │
│  CustomExceptionHandler     ← Friendly error messages           │
│  CustomParameterHandler     ← Parameter validation              │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Picocli** | Java/Kotlin CLI framework with annotation-based command definition |
| **@TopCommand** | Marks `MainCommand` as the entry point for CLI parsing |
| **GlobalMixin** | Shared options injected into all commands via `@Mixin` |
| **Quarkus Integration** | Commands are CDI beans with dependency injection |

## File Reference

| File | Responsibility |
|------|----------------|
| `MainCommand.kt` | Top-level command with subcommand registration |
| `listen/ListenCommand.kt` | Start message consumer, display mascot, wait for exit |
| `config/ConfigCommand.kt` | Display effective configuration |
| `config/ConfigPathHolder.kt` | Resolve config file path from CLI/env/defaults |
| `definitions/DefinitionCommand.kt` | Parent command for definition subcommands |
| `definitions/DefinitionGetCommand.kt` | Get workflow definition by name/version |
| `definitions/DefinitionPostCommand.kt` | Create/update workflow definition from file |
| `definitions/DefinitionDeleteCommand.kt` | Delete workflow definitions |
| `instances/InstanceCommand.kt` | Parent command for instance subcommands |
| `instances/InstanceStartCommand.kt` | Start a workflow instance |
| `migrate/MigrateCommand.kt` | Run Flyway migrations |
| `migrate/MigrateStatusCommand.kt` | Show migration status |
| `GlobalMixin.kt` | Shared `--config`, `--verbose`, `--quiet` options |
| `CustomExceptionHandler.kt` | User-friendly error formatting |

## Commands

### listen

Start the workflow message consumer.

```bash
lemline listen [--metrics-port <port>] [--mock-config <path>]
```

| Option | Description |
|--------|-------------|
| `-m, --metrics-port` | Metrics endpoint port (default: from config) |
| `--mock-config` | Path to mock config for test mode |

### config

Display the effective runtime configuration.

```bash
lemline config
```

### definition

Manage workflow definitions.

```bash
# Get a definition
lemline definition get <namespace>/<name>[:<version>]

# Create/update a definition
lemline definition post <file.yaml> [--force]

# Delete a definition
lemline definition delete <namespace>/<name>[:<version>] [--all-versions]
```

### instance

Manage workflow instances.

```bash
# Start a workflow instance
lemline instance start <namespace>/<name>[:<version>] [--input <json>]
```

### migrate

Run database migrations.

```bash
# Run pending migrations
lemline migrate

# Show migration status
lemline migrate status
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (main application) |
| `lemline-runner-definitions` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **New command** | Create `@Command` class, add to `MainCommand.subcommands` |
| **New option** | Add to `GlobalMixin` for shared options, or to specific command |
| **Custom validation** | Extend `CustomParameterHandler` |

## Configuration Resolution

Config file is resolved in order:

1. CLI argument: `--config=<path>`
2. Environment variable: `LEMLINE_CONFIG`
3. Current directory: `.lemline.yaml`
4. User config: `~/.config/lemline/config.yaml`
5. Home directory: `~/.lemline.yaml`
