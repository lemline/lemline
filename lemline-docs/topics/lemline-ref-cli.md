---
title: Command Syntax and Options
---

# Command Syntax and Options

This reference guide provides comprehensive details about the Lemline command-line interface (CLI), including command syntax, available options, and usage examples.

## CLI Overview

The Lemline CLI provides a command-line interface for interacting with the Lemline workflow engine. It allows you to:

- Manage workflow definitions
- Start and monitor workflow instances
- Configure the Lemline runtime
- Listen for events
- Debug and troubleshoot workflows

## Basic Syntax

The basic syntax for Lemline commands follows this pattern:

```
lemline [global-options] <command> [command-options] [arguments]
```

Where:
- `[global-options]` are options that apply to all commands
- `<command>` is the main command to execute
- `[command-options]` are options specific to the command
- `[arguments]` are positional arguments for the command

## Global Options

These options can be used with any command:

| Option | Description | Default |
|--------|-------------|---------|
| `--help` | Show help information for a command | - |
| `--version` | Show version information | - |
| `--config FILE` | Specify a configuration file | `lemline.yaml` |
| `--verbose` | Enable verbose output | `false` |
| `--quiet` | Suppress all output except errors | `false` |
| `--debug` | Enable debug output | `false` |
| `--no-color` | Disable colored output | `false` |
| `--profile NAME` | Use a specific configuration profile | `default` |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |

## Core Commands

### Workflow Definition Commands

#### `definition` - Manage workflow definitions

```
lemline definition [subcommand] [options]
```

Subcommands:
- `get` - Retrieve workflow definitions
- `post` - Register/update workflow definitions
- `delete` - Remove workflow definitions

##### `definition get` - Retrieve workflow definitions

```
lemline definition get [id] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Specific workflow definition ID | - |
| `--version VERSION` | Specific workflow version | latest |
| `--all` | Get all workflow definitions | `false` |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |

Examples:
```bash
# Get a specific workflow definition
lemline definition get order-processing --version 1.0

# List all workflow definitions
lemline definition get --all

# Get a definition and output as YAML
lemline definition get invoice-processing --format yaml
```

##### `definition post` - Register or update a workflow definition

```
lemline definition post [file] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--file FILE` | Workflow definition file | - |
| `--directory DIR` | Directory containing workflow definitions | - |
| `--update` | Update existing definition if it exists | `false` |
| `--validate-only` | Validate without registering | `false` |

Examples:
```bash
# Register a single workflow definition
lemline definition post orders/order-processing.yaml

# Register all workflow definitions in a directory
lemline definition post --directory workflows/

# Update an existing workflow definition
lemline definition post order-processing.yaml --update
```

##### `definition delete` - Remove a workflow definition

```
lemline definition delete [id] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Workflow definition ID | - |
| `--version VERSION` | Specific workflow version | all versions |
| `--all` | Delete all workflow definitions | `false` |
| `--force` | Skip confirmation prompt | `false` |

Examples:
```bash
# Delete a specific workflow definition version
lemline definition delete order-processing --version 1.0

# Delete all versions of a workflow definition
lemline definition delete order-processing

# Delete all workflow definitions (dangerous!)
lemline definition delete --all --force
```

### Instance Commands

#### `instance` - Manage workflow instances

```
lemline instance [subcommand] [options]
```

Subcommands:
- `start` - Start a new workflow instance
- `get` - Get workflow instance details
- `list` - List workflow instances
- `cancel` - Cancel a workflow instance
- `suspend` - Suspend a workflow instance
- `resume` - Resume a suspended workflow instance
- `retry` - Retry a failed workflow instance

##### `instance start` - Start a new workflow instance

```
lemline instance start [id] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Workflow definition ID | - |
| `--version VERSION` | Workflow definition version | latest |
| `--input JSON` | Input data as a JSON string | `{}` |
| `--input-file FILE` | File containing input data | - |
| `--wait` | Wait for workflow completion | `false` |
| `--timeout DURATION` | Maximum wait time (ISO8601 duration) | `PT5M` |

Examples:
```bash
# Start a workflow with inline JSON input
lemline instance start order-processing --input '{"orderId": "ORD-123", "customerId": "CUST-456"}'

# Start a workflow with input from a file
lemline instance start order-processing --input-file orders/order-123.json

# Start and wait for completion (up to 10 minutes)
lemline instance start data-processing --wait --timeout PT10M
```

##### `instance get` - Get workflow instance details

```
lemline instance get [instanceId] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Instance ID | - |
| `--details` | Include detailed state information | `false` |
| `--history` | Include execution history | `false` |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |

Examples:
```bash
# Get basic instance information
lemline instance get 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a

# Get detailed instance state
lemline instance get 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --details

# Get instance history in JSON format
lemline instance get 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --history --format json
```

##### `instance list` - List workflow instances

```
lemline instance list [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--workflow-id ID` | Filter by workflow ID | all workflows |
| `--status STATUS` | Filter by status | all statuses |
| `--from DATE` | Start date/time | - |
| `--to DATE` | End date/time | - |
| `--limit N` | Maximum number of results | `100` |
| `--offset N` | Results offset for pagination | `0` |
| `--sort FIELD` | Sort field | `startTime` |
| `--order ORDER` | Sort order (asc, desc) | `desc` |

Examples:
```bash
# List all recent workflow instances
lemline instance list

# List running instances of a specific workflow
lemline instance list --workflow-id order-processing --status RUNNING

# List workflows that started in the last 24 hours
lemline instance list --from $(date -d "24 hours ago" --iso-8601=seconds)

# List the oldest 10 completed workflows
lemline instance list --status COMPLETED --limit 10 --sort startTime --order asc
```

##### `instance cancel` - Cancel a workflow instance

```
lemline instance cancel [instanceId] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Instance ID | - |
| `--reason REASON` | Cancellation reason | - |
| `--force` | Force cancellation even if in a non-cancelable state | `false` |

Examples:
```bash
# Cancel a workflow instance
lemline instance cancel 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a

# Cancel with a reason
lemline instance cancel 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --reason "Customer request"

# Force cancellation
lemline instance cancel 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --force
```

##### `instance suspend` - Suspend a workflow instance

```
lemline instance suspend [instanceId] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Instance ID | - |
| `--reason REASON` | Suspension reason | - |
| `--duration DURATION` | Automatic resume after duration (ISO8601) | - |

Examples:
```bash
# Suspend a workflow instance
lemline instance suspend 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a

# Suspend with a reason
lemline instance suspend 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --reason "System maintenance"

# Suspend for 1 hour
lemline instance suspend 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --duration PT1H
```

##### `instance resume` - Resume a suspended workflow instance

```
lemline instance resume [instanceId] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Instance ID | - |
| `--reason REASON` | Resume reason | - |

Examples:
```bash
# Resume a workflow instance
lemline instance resume 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a

# Resume with a reason
lemline instance resume 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --reason "Maintenance complete"
```

##### `instance retry` - Retry a failed workflow instance

```
lemline instance retry [instanceId] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--id ID` | Instance ID | - |
| `--from-position` | Retry from the failed position | `true` |
| `--from-beginning` | Retry from the beginning | `false` |

Examples:
```bash
# Retry a workflow instance from the failed position
lemline instance retry 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a

# Retry a workflow instance from the beginning
lemline instance retry 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a --from-beginning
```

### Config Commands

#### `config` - Manage Lemline configuration

```
lemline config [subcommand] [options]
```

Subcommands:
- `get` - Get configuration values
- `set` - Set configuration values
- `list` - List all configuration
- `init` - Initialize configuration

##### `config get` - Get configuration values

```
lemline config get [key] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--key KEY` | Configuration key | - |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |

Examples:
```bash
# Get a specific configuration value
lemline config get lemline.database.type

# Get the entire database configuration section
lemline config get lemline.database --format yaml
```

##### `config set` - Set configuration values

```
lemline config set [key] [value] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--persist` | Save to configuration file | `true` |

Examples:
```bash
# Set database type
lemline config set lemline.database.type postgresql

# Set messaging broker URL
lemline config set lemline.messaging.kafka.bootstrap.servers localhost:9092
```

##### `config list` - List all configuration

```
lemline config list [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--format FORMAT` | Output format (json, yaml, text) | `text` |
| `--source` | Show configuration sources | `false` |

Examples:
```bash
# List all configuration
lemline config list

# List configuration with sources in YAML format
lemline config list --source --format yaml
```

### Listen Command

#### `listen` - Listen for events and messages

```
lemline listen [subcommand] [options]
```

Subcommands:
- `events` - Listen for workflow events

##### `listen events` - Listen for workflow events

```
lemline listen events [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--type TYPE` | Filter by event type(s) | all types |
| `--workflow-id ID` | Filter by workflow ID | all workflows |
| `--instance-id ID` | Filter by instance ID | all instances |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |
| `--follow` | Continue listening for new events | `true` |
| `--tail N` | Show the most recent N events | `0` |

Examples:
```bash
# Listen for all events
lemline listen events

# Listen for specific event types
lemline listen events --type WORKFLOW_STARTED,WORKFLOW_COMPLETED

# Listen for events from a specific workflow
lemline listen events --workflow-id order-processing

# Show the last 10 events and continue listening
lemline listen events --tail 10
```

### Workflow Command

#### `workflow` - Direct workflow operations

```
lemline workflow [subcommand] [options]
```

Subcommands:
- `run` - Run a workflow directly from a file
- `validate` - Validate a workflow definition
- `visualize` - Generate a visual representation of a workflow

##### `workflow run` - Run a workflow directly from a file

```
lemline workflow run [file] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--file FILE` | Workflow definition file | - |
| `--input JSON` | Input data as a JSON string | `{}` |
| `--input-file FILE` | File containing input data | - |
| `--wait` | Wait for workflow completion | `true` |
| `--timeout DURATION` | Maximum wait time (ISO8601 duration) | `PT5M` |
| `--register` | Register the workflow definition | `false` |

Examples:
```bash
# Run a workflow directly from a file
lemline workflow run order-processing.yaml

# Run with input data
lemline workflow run order-processing.yaml --input '{"orderId": "ORD-123"}'

# Run without waiting for completion
lemline workflow run long-process.yaml --wait false
```

##### `workflow validate` - Validate a workflow definition

```
lemline workflow validate [file] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--file FILE` | Workflow definition file | - |
| `--directory DIR` | Directory containing workflow definitions | - |
| `--quiet` | Show only errors, no success messages | `false` |

Examples:
```bash
# Validate a single workflow definition
lemline workflow validate order-processing.yaml

# Validate all workflow definitions in a directory
lemline workflow validate --directory workflows/
```

##### `workflow visualize` - Generate a visual representation of a workflow

```
lemline workflow visualize [file] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--file FILE` | Workflow definition file | - |
| `--output FILE` | Output file (png, svg, pdf) | - |
| `--format FORMAT` | Output format (png, svg, pdf, dot) | `svg` |
| `--show-data` | Include data flows | `false` |
| `--show-conditions` | Include condition expressions | `false` |

Examples:
```bash
# Generate an SVG visualization
lemline workflow visualize order-processing.yaml --output order-flow.svg

# Generate a PNG with data flows and conditions
lemline workflow visualize order-processing.yaml --output order-detailed.png --format png --show-data --show-conditions
```

### Event Command

#### `events` - Manage events

```
lemline events [subcommand] [options]
```

Subcommands:
- `publish` - Publish an event
- `list` - List historical events

##### `events publish` - Publish an event

```
lemline events publish [type] [payload] [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--type TYPE` | Event type | - |
| `--payload JSON` | Event payload as JSON | - |
| `--file FILE` | File containing event payload | - |
| `--source SOURCE` | Event source | `cli` |

Examples:
```bash
# Publish an event with inline payload
lemline events publish OrderApproved '{"orderId": "ORD-123", "approver": "manager1"}'

# Publish an event with payload from a file
lemline events publish ShipmentUpdate --file events/shipment-update.json

# Publish with a custom source
lemline events publish SystemAlert '{"level": "warning", "message": "Disk space low"}' --source monitoring-system
```

##### `events list` - List historical events

```
lemline events list [options]
```

Options:
| Option | Description | Default |
|--------|-------------|---------|
| `--type TYPE` | Filter by event type(s) | all types |
| `--source SOURCE` | Filter by source | all sources |
| `--from DATE` | Start date/time | - |
| `--to DATE` | End date/time | - |
| `--limit N` | Maximum number of results | `100` |
| `--format FORMAT` | Output format (json, yaml, text) | `text` |

Examples:
```bash
# List recent events
lemline events list

# List specific event types from today
lemline events list --type OrderCreated,OrderCompleted --from $(date -d "today 00:00" --iso-8601=seconds)

# List events from a specific source in JSON format
lemline events list --source payment-service --format json
```

## Command File Syntax

You can create command files to run multiple commands in sequence:

```yaml
# commands.yaml
commands:
  - name: Register workflows
    command: definition post
    args:
      directory: workflows/

  - name: Start order workflow
    command: instance start
    args:
      id: order-processing
      version: '1.0'
      input-file: orders/sample-order.json

  - name: List running instances
    command: instance list
    args:
      status: RUNNING
      format: json
```

Run with:
```bash
lemline run commands.yaml
```

## Environment Variables

Lemline CLI supports these environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `LEMLINE_CONFIG` | Path to configuration file | `lemline.yaml` |
| `LEMLINE_PROFILE` | Configuration profile | `default` |
| `LEMLINE_FORMAT` | Default output format | `text` |
| `LEMLINE_VERBOSE` | Enable verbose output | `false` |
| `LEMLINE_COLOR` | Enable colored output | `true` |
| `LEMLINE_DATABASE_URL` | Database connection URL | - |
| `LEMLINE_DATABASE_USERNAME` | Database username | - |
| `LEMLINE_DATABASE_PASSWORD` | Database password | - |
| `LEMLINE_MESSAGING_TYPE` | Messaging type (kafka, rabbitmq) | - |
| `LEMLINE_API_URL` | Lemline API URL | - |

Example:
```bash
LEMLINE_FORMAT=json LEMLINE_PROFILE=production lemline instance list
```

## Configuration Profiles

You can define multiple configuration profiles in `lemline.yaml`:

```yaml
profiles:
  default:
    lemline:
      database:
        type: h2
        url: jdbc:h2:mem:lemline;DB_CLOSE_DELAY=-1

  development:
    lemline:
      database:
        type: postgresql
        url: jdbc:postgresql://localhost:5432/lemline_dev
        username: lemline
        password: lemline_dev

  production:
    lemline:
      database:
        type: postgresql
        url: jdbc:postgresql://db.example.com:5432/lemline_prod
        username: ${DB_USERNAME}
        password: ${DB_PASSWORD}
      messaging:
        type: kafka
        bootstrap.servers: kafka1:9092,kafka2:9092,kafka3:9092
```

Use a profile with:
```bash
lemline --profile production instance list
```

## Exit Codes

The Lemline CLI uses these exit codes:

| Code | Description |
|------|-------------|
| 0 | Success |
| 1 | General error |
| 2 | Command-line parsing error |
| 3 | Configuration error |
| 4 | Connection error |
| 5 | Entity not found |
| 6 | Validation error |
| 7 | Execution error |
| 8 | Timeout error |
| 9 | Authentication/authorization error |
| 130 | Interrupted by user (Ctrl+C) |

## Next Steps

- Learn about [exit codes and error states](lemline-ref-errors.md)
- Explore [configuration options](lemline-ref-config.md)
- Understand [environment variables](lemline-ref-env-vars.md)