<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-cli

Picocli-based CLI commands for the Lemline runtime.

## Key Files

| File | Description |
|------|-------------|
| `MainCommand.kt` | Top-level Picocli command |
| `GlobalMixin.kt` | Shared CLI options (config path, verbosity) |
| `VersionProvider.kt` | Dynamic version from build metadata |
| `CustomExceptionHandler.kt` | CLI error formatting |
| `CustomParameterHandler.kt` | Parameter validation |
| `CommandLineExtensions.kt` | Picocli extension utilities |
| `listen/` | `listen` command - starts workflow worker |
| `config/` | `config` command - displays configuration |
| `definitions/` | `definition` command - manages workflow definitions |
| `instances/` | `instance` command - manages workflow instances |
| `migrate/` | `migrate` command - runs database migrations |
| `common/` | Shared CLI utilities |
| `exceptions/` | CLI-specific exception types |

## For AI Agents

### Working In This Directory
- Each CLI command is a Picocli `@Command` subclass
- Use `GlobalMixin` for shared options
- Commands are registered in `MainCommand`
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-cli:test
```

### Dependencies
- Internal: `lemline-runner-common`, `lemline-common`
- External: Picocli, Quarkus

<!-- MANUAL: -->
