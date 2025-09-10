---
title: Getting Started Fast
---

# Getting Started Fast

This guide will help you quickly set up Lemline and run your first workflow. For more detailed learning, check out
the [Hello, Workflow! tutorial](lemline-tutorial-hello.md).

## Installing Lemline

### Option 1: Download the Prebuilt Binary

You can download nightly builds from
our [GitHub releases page](https://github.com/lemline/lemline/releases/tag/nightly).

#### Linux (x86_64)

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download and extract the Linux binary
curl -L https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-linux-x86_64.tar.gz -o lemline.tar.gz
tar -xzf lemline.tar.gz
rm lemline.tar.gz

# Make it executable
chmod +x lemline

# Run Lemline (add to your PATH for easier access)
./lemline --help
```

#### macOS (Apple Silicon/ARM64)

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download and extract the macOS binary
curl -L https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-macos-arm64.tar.gz -o lemline.tar.gz
tar -xzf lemline.tar.gz
rm lemline.tar.gz

# Make it executable
chmod +x lemline

# Run Lemline (add to your PATH for easier access)
./lemline --help
```

#### Windows (x86_64)

1. Download
   the [Windows ZIP file](https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-windows-x86_64.zip)
2. Extract the ZIP to a location of your choice (e.g., `C:\Lemline`)
3. Run `lemline.exe` from the Command Prompt or PowerShell

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
java -jar lemline-runner.jar --help
```

### Option 2: Build from Source

```bash
# Clone the repository
git clone https://github.com/lemline/lemline.git
cd lemline

# Build with Gradle
./gradlew :lemline-runner:build -x test

# The runner JAR will be in lemline-runner/build/libs/
```

## Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: hello-workflow
  version: '0.1.0'
  title: Hello Workflow
do:
  - greeting:
      set:
        message: "Hello, Lemline!"
  - logMessage:
      call: log
      with:
        message: ${ .message }
```

This simple workflow:

1. Sets a message variable
2. Logs the message to the console
3. Ends the workflow

## Running Your Workflow

```bash
# Run the workflow using the Lemline runner
java -jar lemline-runner.jar workflow run hello.yaml

# Or if you have the Lemline CLI installed:
lemline workflow run hello.yaml
```

You should see output including your "Hello, Lemline!" message.

## Exploring Next Steps

Now that you've run your first workflow, here are some quick follow-up steps:

### Modify the Workflow

Try changing the message in the workflow and running it again to see how changes affect the output.

### Add More Tasks

Extend the workflow with additional set tasks or try using a different task type like wait or switch.

### Connect to HTTP Services

For a more useful example, try creating a workflow that makes an HTTP request:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: http-workflow
  version: '0.1.0'
  title: HTTP Request Workflow
do:
  - makeRequest:
      call: http
      with:
        method: GET
        endpoint: https://jsonplaceholder.typicode.com/todos/1
  - displayResult:
      set:
        result: ${ $workflow.makeRequest.result }
```

## Try a Ready-to-Run Sample Project

Lemline comes with several example workflows that demonstrate different features:

```bash
# List available examples
java -jar lemline-runner.jar examples list

# Run a specific example
java -jar lemline-runner.jar examples run star-wars
```

## What's Next?

Now that you've had a quick start with Lemline, you might want to:

- Follow the more detailed [Hello, Workflow! tutorial](lemline-tutorial-hello.md)
- Learn how to [define workflows](lemline-howto-define-workflow.md) in more depth
- Explore [HTTP calls](lemline-howto-http.md) and other service integrations
- Understand [passing data between tasks](lemline-howto-data-passing.md)

For a more conceptual understanding, check out [How Lemline Executes Workflows](lemline-explain-execution.md) in the
Explanations section.
