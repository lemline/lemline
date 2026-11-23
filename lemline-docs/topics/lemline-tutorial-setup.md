---
title: Setting Up A Local Environment
---

# Setting Up A Local Environment

Run Lemline on one machine with SQLite. Lemline stores workflow definitions, queued messages, and workflow history in local files. Use a writable directory on a local filesystem.

## 1. Download the Lemline binary

Create a project directory and open it in your terminal:

```bash
mkdir lemline-project
cd lemline-project
```

Download and extract the native binary for your platform, then check that it reports version `%version%`:

<tabs group="platform">
<tab id="macos" title="macOS (ARM64)" group-key="macos">

```bash
# Download and extract the native binary
curl -fL https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-macos-arm64.tar.gz | tar -xz

# Verify the installation
bin/lemline --version
```

</tab>
<tab id="linux" title="Linux (x86_64)" group-key="linux">

```bash
# Download and extract the native binary
curl -fL https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-linux-x86_64.tar.gz | tar -xz

# Verify the installation
bin/lemline --version
```

</tab>
<tab id="windows" title="Windows (x86_64)" group-key="windows">

```powershell
# Download and extract the native binary
Invoke-WebRequest -Uri "https://github.com/lemline/lemline/releases/download/v%version%/lemline-v%version%-windows-x86_64.zip" -OutFile "lemline.zip"
Expand-Archive -Path "lemline.zip" -DestinationPath "."
Remove-Item "lemline.zip"

# Verify the installation
bin\lemline.exe --version
```

</tab>
</tabs>

## 2. Configure the SQLite files

From `lemline-project`, run the configuration wizard. In PowerShell, use `.\bin\lemline.exe` for the remaining commands.

```bash
bin/lemline config create --output .lemline.yaml
```

1. At **Select messaging broker**, enter `sqlite`.
2. At **Select database**, enter `sqlite` for orchestration.
3. At **Select analytics database**, press Enter to accept `sqlite` for workflow history.

For a script, the equivalent command is:

```bash
bin/lemline config create --messaging sqlite --database sqlite --analytics sqlite --output .lemline.yaml
```

Lemline automatically reads `.lemline.yaml` from the current directory. The generated configuration uses three separate files: `orchestration.db` for definitions and durable operations, `messaging.db` for queues, and `analytics.db` for workflow history.

To let the worker run workflows from the tutorials, add this line directly below the existing `lemline:` key, indented to the same level as `database:`:

```yaml
  namespaces: [default, examples, tutorial]
```

Initialize the database tables:

```bash
bin/lemline migrate --force
```

## 3. Verify the setup

Start the worker from `lemline-project`:

```bash
bin/lemline listen --info
```

Leave the worker running and list the registered definitions from a second terminal in the same directory:

```bash
bin/lemline definition get
```

On a fresh project, expect `No workflows found.` Stop the worker with Ctrl+C, then continue with [Hello, Workflow!](lemline-tutorial-hello.md).
