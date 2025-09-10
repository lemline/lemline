# Lemline CLI Strategy

This document outlines the command-line interface (CLI) for Lemline, focusing on user experience,
consistency, and extensibility.

## Overview

The Lemline CLI provides a unified interface for managing workflow definitions and interacting with the Lemline runtime. It follows
modern CLI design principles using picocli.

## Command Structure

The CLI follows a hierarchical command structure:

```bash
lemline [global-options] <command> [command-options] [arguments]
```

### Global Options

These options are available for all commands:

```bash
--config, -c <path>     # Path to configuration file
--debug                # Set log level to DEBUG
--info                 # Set log level to INFO
--warn                 # Set log level to WARN
--error                # Set log level to ERROR
--help, -h             # Show help
--version              # Show version information
```

## Core Commands

### Definition Management

```bash
# List available workflow definitions
# Print a list of workflow definitions available in the database
lemline definition get

# Show workflow definition details
# Print the workflow description in YAML (default) or JSON format
# If version is not provided, returns the last version
lemline definition get <workflow-name> [<workflow-version>] [--format YAML|JSON]

# Create new workflow definitions
# Create workflows from definition files or directories
lemline definition post --file <file-name> [--file <file-name>...] [--force]
lemline definition post --directory <directory> [--recursive] [--force]

# Delete a workflow definition
# Delete workflows by name, version, or interactively
lemline definition delete [<workflow-name>] [<workflow-version>] [--force]
```

### Instance Management

```bash
# Start a workflow instance
# Create and start a new workflow instance with the given name and optional version
lemline instance start <workflow-name> [<workflow-version>] [--input <json-string>]
```

### Configuration Management

```bash
# Show current configuration
lemline config [--format YAML|PROPERTIES] [--all]
```

### Runtime Management

```bash
# Start the runtime listener for processing workflow events
lemline listen
```

## Configuration Discovery

The CLI looks for configuration files in the following order:

1. Command line argument: `--config=<path>` or `-c <path>`
2. Environment variable: `LEMLINE_CONFIG`
3. Current directory: `.lemline.yaml`
4. User configuration directory:
   - `~/.config/lemline/config.yaml`
   - `~/.lemline.yaml`

## Environment Variables

The CLI respects the following environment variables:

```bash
LEMLINE_CONFIG       # Path to configuration file
```

## Examples

### Basic Usage

```bash
# Start the runtime listener
lemline listen

# Create workflow definitions
lemline definition post --file my-workflow.yaml

# Start a workflow instance
lemline instance start my-workflow --input '{"key": "value"}'
```

### Advanced Usage

```bash
# Use a specific configuration file
lemline --config /etc/lemline/prod.yaml listen

# View configuration
lemline config --all --format PROPERTIES

# Work with multiple definition files
lemline definition post --directory workflows --recursive

# Delete a specific workflow version with force option
lemline definition delete my-workflow 1.0.0 --force
```

## Implementation Guidelines

1. **Command Structure**
   - Use a consistent verb-noun pattern
   - Group related commands under common prefixes
   - Provide clear, concise command names

2. **Error Handling**
   - Provide clear, actionable error messages
   - Suggest solutions for common errors

3. **Help System**
   - Include examples in help text
   - Provide context-sensitive help
   - Document all options and arguments

4. **Configuration**
   - Support both file-based and environment-based configuration
   - Allow configuration overrides via command line
   - Validate configuration before use

## Future Considerations

1. **Additional Commands**
   - Add more instance management commands (list, show, terminate)
   - Implement workflow validation features
   - Add support for workflow templates

2. **Interactive Mode**
   - Develop a fully interactive CLI mode
   - Support for tab completion
   - Command history

3. **Monitoring and Debugging**
   - Add performance monitoring commands
   - Implement debugging tools
   - Add support for tracing 
