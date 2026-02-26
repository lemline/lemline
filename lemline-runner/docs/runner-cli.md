# Lemline Runner - CLI Commands

This document covers the CLI commands available in the lemline-runner module.

## Entry Point

**Key file**: [LemlineApplication.kt](../src/main/kotlin/com/lemline/runner/cli/LemlineApplication.kt)

## Command Structure

```
lemline [global-options] <command> [command-options]
```

## Global Options

| Option                | Description                 |
|-----------------------|-----------------------------|
| `-c, --config <path>` | Configuration file location |
| `--debug`             | Set log level to DEBUG      |
| `--info`              | Set log level to INFO       |
| `--warn`              | Set log level to WARN       |
| `--error`             | Set log level to ERROR      |
| `-h, --help`          | Show help                   |
| `-V, --version`       | Show version                |

**Key file**: [GlobalMixin.kt](../src/main/kotlin/com/lemline/runner/cli/GlobalMixin.kt)

---

## Commands

### `listen`

Starts the workflow and database message consumers. This is the main runtime mode.

```bash
lemline listen [--metrics-port <port>]
```

| Option                | Description                              |
|-----------------------|------------------------------------------|
| `-m, --metrics-port`  | Metrics endpoint port (default: 8080)    |

**Key file**: [ListenCommand.kt](../src/main/kotlin/com/lemline/runner/cli/ListenCommand.kt)

**Example:** `lemline listen --info --metrics-port 9090`

---

### `config`

Displays the current configuration.

```bash
lemline config [-f yaml|properties] [-a]
```

| Option | Description                               |
|--------|-------------------------------------------|
| `-f`   | Output format: `yaml` or `properties`     |
| `-a`   | Show all properties, not just `lemline.*` |

**Key file**: [ConfigCommand.kt](../src/main/kotlin/com/lemline/runner/cli/ConfigCommand.kt)

---

### `definition`

Manage workflow definitions.

**Key files**: [cli/definition/](../src/main/kotlin/com/lemline/runner/cli/definition/)

#### Get Definitions

```bash
lemline definition get [namespace] [name] [version] [-f yaml|json]
```

| Argument    | Description                          |
|-------------|--------------------------------------|
| `namespace` | Filter by namespace (optional)       |
| `name`      | Filter by name (requires namespace)  |
| `version`   | Filter by version (requires name)    |
| `-f`        | Output format: `yaml` (default) or `json` |

**Examples:**

```bash
lemline definition get                                    # List all
lemline definition get my-namespace my-workflow 1.0.0     # Get specific version
lemline definition get -f json                            # Output as JSON
```

#### Upload Definition

```bash
lemline definition post <file>
```

| Argument | Description                                     |
|----------|-------------------------------------------------|
| `file`   | YAML file or directory (recursive) to upload    |

**Example:** `lemline definition post ./workflows/`

#### Delete Definition

```bash
lemline definition delete <namespace> <name> <version>
```

---

### `gateway`

Manage the gRPC gateway server.

**Key files**: [cli/gateway/](../src/main/kotlin/com/lemline/runner/cli/gateway/)

#### Start Gateway

```bash
lemline gateway start [--grpc-port <port>] [--metrics-port <port>]
```

| Option                | Description                              |
|-----------------------|------------------------------------------|
| `-g, --grpc-port`     | gRPC port (overrides config)             |
| `-m, --metrics-port`  | Metrics endpoint port (default: 8080)    |

**Example:** `lemline gateway start --grpc-port 9090 --metrics-port 8081`

---

### `instance`

Manage workflow instances.

**Key files**: [cli/instance/](../src/main/kotlin/com/lemline/runner/cli/instance/)

#### Start Instance

```bash
lemline instance start <namespace> <name> [version] [-i <input-json>] [-z <timezone>]
```

| Argument    | Description                             |
|-------------|-----------------------------------------|
| `namespace` | Workflow namespace                      |
| `name`      | Workflow name                           |
| `version`   | Workflow version (optional, uses latest)|

| Option        | Description               |
|---------------|---------------------------|
| `-i, --input` | Input data as JSON string |
| `-z, --zone`  | Timezone for the workflow |

**Example:** `lemline instance start my-namespace my-workflow -i '{"userId": 123}'`

---

### `migrate`

Run database migrations.

**Key files**: [cli/migrate/](../src/main/kotlin/com/lemline/runner/cli/migrate/)

#### Run Migrations

```bash
lemline migrate [--pretend] [--force]
```

| Option      | Description                               |
|-------------|-------------------------------------------|
| `--pretend` | Show what would be done without executing |
| `--force`   | Force migration even if validation fails  |

#### Migration Status

`lemline migrate status` - Shows the current state of all migrations.

---

## Running the Application

### Development Mode

```bash
# Run in Quarkus dev mode (hot reload)
./gradlew :lemline-runner:quarkusDev

# Run with custom config
QUARKUS_CONFIG_LOCATIONS=application.yml ./gradlew :lemline-runner:quarkusDev
```

### Production JAR

```bash
# Build
./gradlew :lemline-runner:build

# Run with default config
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar listen --info

# Run with custom config
LEMLINE_CONFIG=/path/to/config.yaml java -jar lemline-runner/build/quarkus-app/quarkus-run.jar listen
```

### Native Binary

```bash
# Build native image (Linux)
./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true

# Build native image (macOS)
./gradlew clean :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false

# Run native binary
./lemline-runner/build/lemline-runner-*-runner listen
```

---

## Adding a New CLI Command

1. Create command class in `src/main/kotlin/com/lemline/runner/cli/`
2. Implement `Runnable` or `Callable<Int>`
3. Add `@Command` annotation with name and description
4. Register in parent command's `subcommands` array
5. Inject dependencies using `@Inject`

**Example:**

```kotlin
@Command(
    name = "my-command",
    description = ["Description of my command"]
)
class MyCommand : Runnable {
    @Inject
    lateinit var someService: SomeService

    @Option(names = ["-o", "--option"], description = ["Option description"])
    var option: String? = null

    override fun run() {
        // Command implementation
    }
}
```
