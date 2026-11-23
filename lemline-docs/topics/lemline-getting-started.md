---
title: Getting Started Fast
---

# Getting Started Fast

This guide will help you quickly set up Lemline and run your first workflow. For more detailed learning, check out
the [Hello, Workflow! tutorial](lemline-tutorial-hello.md).

## Installing Lemline

### Option 1: Download the Prebuilt Binary

You can download nightly builds for Linux, Windows and the JAR from
our [GitHub releases page](https://github.com/lemline/lemline/releases/tag/nightly).
There is no nightly macOS build: the macOS binary comes with each tagged version.

#### Linux (x86_64)

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download and extract the Linux binary
curl -L https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-linux-x86_64.tar.gz -o lemline.tar.gz
tar -xzf lemline.tar.gz
rm lemline.tar.gz

# Run Lemline (add ~/lemline/bin to your PATH for easier access)
./bin/lemline --help
```

#### macOS (Apple Silicon/ARM64)

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download and extract the macOS binary of version %version%
curl -L https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-macos-arm64.tar.gz -o lemline.tar.gz
tar -xzf lemline.tar.gz
rm lemline.tar.gz

# Run Lemline (add ~/lemline/bin to your PATH for easier access)
./bin/lemline --help
```

#### Windows (x86_64)

1. Download
   the [Windows ZIP file](https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-windows-x86_64.zip)
2. Extract the ZIP to a location of your choice (e.g., `C:\Lemline`)
3. Run `bin\lemline.exe` from the Command Prompt or PowerShell

#### Other Platforms (Java JAR)

For platforms not listed above, you can use the Java JAR version:

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download and extract the JAR
curl -L https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-jar.zip -o lemline-jar.zip
unzip lemline-jar.zip
rm lemline-jar.zip

# Run using Java
java -jar lemline.jar --help
```

### Option 2: Build from Source

```bash
# Clone the repository
git clone https://github.com/lemline/lemline.git
cd lemline

# Build with Gradle
./gradlew :lemline-runner:build -x test

# Run it with: java -jar lemline-runner/build/quarkus-app/quarkus-run.jar --help
```

## Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

This simple workflow sets a `message` value, which becomes the workflow output, and ends.

## Running Your Workflow

Running a workflow takes three steps: register the definition, start a runner, and start an instance. The runner and the CLI must share a database and a message broker, so first set up the local environment described in [Setting Up A Local Environment](lemline-tutorial-setup.md). Its configuration file also makes the runner serve the `examples` namespace used here.

```bash
# Register the definition
lemline definition post -f hello.yaml

# In a separate terminal: start a runner and leave it running
lemline listen --debug

# Start an instance
lemline instance start examples hello-workflow
```

`instance start` prints the id of the new instance. With `--debug`, the runner logs the workflow output, including your "Hello, Lemline!" message.

## Exploring Next Steps

Now that you've run your first workflow, here are some quick follow-up steps:

### Modify the Workflow

Try changing the message in the workflow and running it again to see how changes affect the output.

### Add More Tasks

Extend the workflow with additional set tasks or try using a different task type like wait or switch.

### Connect to HTTP Services

For a more useful example, try creating a workflow that makes an HTTP request:

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

## Try a Ready-to-Run Sample Project

The repository's `examples/` directory contains Docker Compose setups and configuration files for Kafka, RabbitMQ, PostgreSQL and MySQL. See `examples/README.md` in the repository.

## What's Next?

Now that you've had a quick start with Lemline, you might want to:

- Follow the more detailed [Hello, Workflow! tutorial](lemline-tutorial-hello.md)
- Learn how to [define workflows](lemline-howto-define-workflow.md) in more depth
- Explore [HTTP calls](lemline-howto-http.md) and other service integrations
- Understand [passing data between tasks](lemline-howto-data-passing.md)

For a more conceptual understanding, check out [How Lemline Executes Workflows](lemline-explain-execution.md) in the
Explanations section.
