# Lemline CLI Strategy

This document outlines the proposed command-line interface (CLI) strategy for Lemline, focusing on user experience,
consistency, and extensibility.

## Overview

The Lemline CLI provides a unified interface for managing workflows and interacting with the Lemline runtime. It follows
modern CLI design principles and provides both interactive and non-interactive modes.

## Command Structure

The CLI follows a hierarchical command structure:

```bash
lemline [global-options] <command> [command-options] [arguments]
```

### Global Options

These options are available for all commands:

```bash
--config, -c <path>     # Path to configuration file (default: ./application.yaml)
--profile, -p <name>    # Profile to use (dev, test, prod)
--verbose, -v           # Enable verbose output
--quiet, -q            # Suppress non-essential output
--help, -h             # Show help
--version              # Show version information
```

## Core Commands

### Workflow Management

```bash
# List available workflows
# Print a list of workflow name + workflow version available in the database
# format json: [{name: ..., version: [1.0, 2.0, 3.0]}]
lemline workflow get [--format json|yaml|table]

# Show workflow details
# Print the  workflow description in Yaml (default) or beautified Json format
# if version is not provided, returns the last version
lemline workflow get <workflow-name> [<workflow-version>] [--format json|yaml]

# Create a new workflow
# Create a workflow, giving its description or a file name containing the description
# Fails if the workflow already exist
lemline workflow post [definition] [--file <file-name>]

# Update an existing workflow
# Update the definition of an existing workflow
# should require an interactive confirmation (except if --force)
lemline workflow put [definition] [--file <file-name>] [--force]

# Delete a workflow
# Delete the last versions of a workflow or a specific version if provided
# should require an interactive confirmation (except if --force)
lemline workflow delete <workflow-name> [<workflow-version>] 

# Validate a workflow definition
lemline workflow validate [definition] [--file <name>] 
```

### Runtime Management

```bash
# Start the runtime
lemline runtime start [--daemon]

# Stop the runtime
lemline runtime stop

# Show runtime status
lemline runtime status [--format json|yaml|table]

# Show runtime logs
lemline runtime logs [--follow] [--tail <lines>] [--since <duration>]

# Restart the runtime
lemline runtime restart
```

### Instance Management

```bash
# List workflow instances
lemline instance list [--workflow <id>] [--status <status>] [--format json|yaml|table]

# Show instance details
lemline instance show <instance-id> [--format json|yaml|table]

# Create a new instance
lemline instance create <workflow-id> [--input <json-file>]

# Terminate an instance
lemline instance terminate <instance-id> [--reason <reason>]

# Show instance logs
lemline instance logs <instance-id> [--follow] [--tail <lines>]
```

### Configuration Management

```bash
# Show current configuration
lemline config [--format json|yaml|properties]
```

## Interactive Mode

The CLI provides an interactive mode for users who prefer a more guided experience:

```bash
lemline interactive
```

In interactive mode:

- Commands are presented in a menu-driven interface
- Context-sensitive help is available
- Command history is maintained
- Tab completion is supported
- Common operations are streamlined

## Output Formats

The CLI supports multiple output formats:

- `table`: Human-readable table format (default for interactive use)
- `json`: JSON format for programmatic use
- `yaml`: YAML format for configuration files

## Environment Variables

The CLI respects the following environment variables:

```bash
LEMLINE_CONFIG_PATH    # Path to configuration file
LEMLINE_PROFILE       # Default profile to use
LEMLINE_LOG_LEVEL     # Log level (DEBUG, INFO, WARN, ERROR)
LEMLINE_NO_COLOR      # Disable colored output
```

## Examples

### Basic Usage

```bash
# Start the runtime
lemline runtime start

# Create a workflow
lemline workflow create my-workflow.yaml

# Create and start a workflow instance
lemline instance create my-workflow --input input.json

# Monitor instance progress
lemline instance logs my-instance --follow
```

### Advanced Usage

```bash
# Use a specific configuration file
lemline --config /etc/lemline/prod.yaml runtime start

# Run in production profile
lemline --profile prod workflow list

# Get JSON output for scripting
lemline --format json instance list > instances.json

# Validate workflow and configuration
lemline workflow validate my-workflow.yaml && \
lemline config validate prod.yaml
```

## Implementation Guidelines

1. **Command Structure**
    - Use a consistent verb-noun pattern
    - Group related commands under common prefixes
    - Provide clear, concise command names

2. **Error Handling**
    - Provide clear, actionable error messages
    - Include error codes for programmatic handling
    - Suggest solutions for common errors

3. **Help System**
    - Include examples in help text
    - Provide context-sensitive help
    - Document all options and arguments

4. **Output Formatting**
    - Support multiple output formats
    - Ensure consistent formatting
    - Provide clear headers and separators

5. **Configuration**
    - Support both file-based and environment-based configuration
    - Allow configuration overrides via command line
    - Validate configuration before use

6. **Extensibility**
    - Design for easy addition of new commands
    - Support plugins for additional functionality
    - Maintain backward compatibility

## Future Considerations

1. **Plugin System**
    - Allow third-party plugins
    - Provide plugin management commands
    - Support plugin-specific configuration

2. **API Integration**
    - Add commands for API key management
    - Support remote operation
    - Enable API documentation generation

3. **Advanced Features**
    - Add support for workflow templates
    - Implement workflow versioning
    - Add support for workflow dependencies

4. **Monitoring and Debugging**
    - Add performance monitoring commands
    - Implement debugging tools
    - Add support for tracing 
