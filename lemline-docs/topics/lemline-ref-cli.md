---
title: Command Syntax and Options
---

# Command Syntax and Options

This reference lists every command of the Lemline command-line interface, with its options and examples.

## CLI Overview

The `lemline` command lets you:

- register, read and delete workflow definitions
- start workflow instances
- run runners (`listen`) and the gRPC gateway (`gateway start`)
- create and inspect the configuration
- apply database migrations
- replay messages parked as failures

Instances are observed through the gateway and the dashboard; the CLI has no command to list, cancel, suspend or resume instances.

## Basic Syntax

```
lemline <command> [<subcommand>] [options] [arguments]
```

## Global Options

These options are accepted by every command and subcommand:

| Option | Description |
|--------|-------------|
| `-h`, `--help` | Show help for the command |
| `-V`, `--version` | Show the Lemline version |
| `-c`, `--config <path>` | Configuration file to use (see [Configuring the Lemline Runner](lemline-howto-config.md)) |
| `--debug`, `--info`, `--warn`, `--error` | Set the log level (default: `ERROR`) |
| `--json` | Write logs in JSON |

## Commands

| Command | Purpose |
|---------|---------|
| `definition get` | Show or list workflow definitions |
| `definition post` | Register workflow definitions from files |
| `definition delete` | Delete workflow definitions |
| `instance start` | Start a workflow instance |
| `listen` | Run a runner: consume messages and execute workflows |
| `gateway start` | Run the gRPC gateway |
| `config show` | Print the effective configuration |
| `config create` | Generate a configuration file |
| `migrate` | Apply pending database migrations |
| `migrate status` | Show the state of database migrations |
| `failures replay` | Re-send messages parked as failures |

### `definition get`

```
lemline definition get [<namespace> [<name> [<version>]]] [-f yaml|json]
```

- With namespace, name and version: prints that definition.
- Otherwise: lists the matching definitions and lets you pick one by number (`q` quits). When only one matches, it is printed directly.

| Option | Description | Default |
|--------|-------------|---------|
| `-f`, `--format` | `yaml` or `json` | `yaml` |

```bash
# List all definitions
lemline definition get

# Print one version as JSON
lemline definition get default order-processing 1.0.0 --format json
```

### `definition post`

```
lemline definition post (-f <file> | -d <directory>) [-r] [-F]
```

Registers the definitions found in the given files. The namespace, name and version come from each file's `document` section.

| Option | Description |
|--------|-------------|
| `-f`, `--file <path>` | A definition file (repeatable) |
| `-d`, `--directory <path>` | A directory of definition files (repeatable) |
| `-r`, `--recursive` | Also read subdirectories |
| `-F`, `--force` | Replace a definition that already exists with the same name and version |

```bash
lemline definition post -f order-processing.yaml
lemline definition post -d ./workflows -r
```

### `definition delete`

```
lemline definition delete <namespace> [<name> [<version>]] [-F]
```

Without `--force`, lists the matching definitions, lets you pick one (or `*` for all of them) and asks for confirmation. With `--force`, deletes without asking:

- `<namespace>`: every definition of the namespace
- `<namespace> <name>`: every version of that workflow
- `<namespace> <name> <version>`: that version only

```bash
lemline definition delete default order-processing 1.0.0
```

### `instance start`

```
lemline instance start <namespace> <name> [<version>] [-i <json>] [-z <timezone>]
```

Starts a new instance and prints its id. Without a version, the latest version is used.

| Option | Description | Default |
|--------|-------------|---------|
| `-i`, `--input <json>` | Workflow input, as JSON | `{}` |
| `-z`, `--zone <timezone>` | Time zone used by the workflow schedule, e.g. `Europe/Paris` | system time zone |

```bash
lemline instance start default order-processing --input '{"orderId": "123"}'
```

The command returns once the instance is started; it does not wait for the result.

### `listen`

```
lemline listen [-m <port>] [--mock-config <file>]
```

Runs a runner until it is stopped. The runner consumes commands, events and CloudEvents for its namespaces, executes workflows and runs the background tasks (waits, retries, schedules, cleanup).

| Option | Description | Default |
|--------|-------------|---------|
| `-m`, `--metrics-port <port>` | Port of the metrics and health endpoints | `lemline.metrics.port` (`8080`) |
| `--mock-config <file>` | YAML or JSON file of mocked activity responses; turns on test mode | - |

### `gateway start`

```
lemline gateway start [-g <port>] [-m <port>]
```

Runs the gRPC gateway until it is stopped. See [gRPC Gateway Reference](lemline-ref-gateway.md).

| Option | Description | Default |
|--------|-------------|---------|
| `-g`, `--grpc-port <port>` | gRPC port | `lemline.gateway.grpc.port` (`9000`) |
| `-m`, `--metrics-port <port>` | Port of the metrics and health endpoints | `lemline.metrics.port` (`8080`) |

### `config show`

```
lemline config show [-f yaml|properties] [-a]
```

Prints the configuration Lemline will use, after merging the file, environment variables and system properties.

| Option | Description | Default |
|--------|-------------|---------|
| `-f`, `--format` | `yaml` or `properties` | `yaml` |
| `-a`, `--all` | Include non-`lemline.*` properties | off |

### `config create`

```
lemline config create -m <broker> -d <database> [-o <path>] [--force] [--dry-run]
```

Generates a configuration file. In a terminal, missing choices are asked interactively. The default output name `lemline.yaml` is not searched automatically: pass it with `--config`, or write `-o .lemline.yaml`.

| Option | Description | Default |
|--------|-------------|---------|
| `-m`, `--messaging` | `kafka`, `rabbitmq` or `pgmq` | asked |
| `-d`, `--database` | `postgresql` or `mysql` | asked |
| `-o`, `--output <path>` | File to write | `lemline.yaml` |
| `--force` | Overwrite an existing file | off |
| `--dry-run` | Print the file instead of writing it | off |

### `migrate`

```
lemline migrate [--pretend] [--force]
lemline migrate status
```

`migrate` lists pending database migrations, asks for confirmation and applies them. `migrate status` prints every migration with its state.

| Option | Description |
|--------|-------------|
| `--pretend` | List pending migrations without applying them |
| `--force` | Apply without asking for confirmation |

### `failures replay`

```
lemline failures replay [--id <uuid> | --reason <reason>] [--dry-run] [-y]
```

Re-sends messages that were parked in the failures table to their broker destination. Rows sent successfully are deleted; rows that cannot be decoded are reported and kept.

| Option | Description | Default |
|--------|-------------|---------|
| `--id <uuid>` | Replay a single failure | - |
| `--reason <reason>` | Replay every failure with this reason | `foreign_namespace` |
| `--dry-run` | List what would be replayed | off |
| `-y`, `--yes` | Do not ask for confirmation | off |

## Environment Variables

| Variable | Description |
|----------|-------------|
| `LEMLINE_CONFIG` | Configuration file to use when `--config` is not given |

Every configuration property can also be set as an environment variable. See [Environment Variables Reference](lemline-ref-env-vars.md).

## Related Resources

- [Configuring the Lemline Runner](lemline-howto-config.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [gRPC Gateway Reference](lemline-ref-gateway.md)
