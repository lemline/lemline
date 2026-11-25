---
title: Setting Up A Local Environment
---

# Setting Up A Local Environment

Run Lemline on one machine with SQLite. Lemline stores workflow definitions, queued messages, and workflow history in local files. Use a writable directory on a local filesystem.

## 1. Install Lemline

Create a project directory and open it in your terminal:

```bash
mkdir lemline-project
cd lemline-project
```

SQLite support was added after version 0.7.0. Use a build that lists `sqlite` for messaging, orchestration and the `--analytics` option in `config create --help`.

<tabs group="platform">
<tab id="linux" title="Linux (x86_64)" group-key="linux">

```bash
curl -fL https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-linux-x86_64.tar.gz -o lemline.tar.gz
tar -xzf lemline.tar.gz
bin/lemline config create --help
```

</tab>
<tab id="windows" title="Windows (x86_64)" group-key="windows">

```powershell
Invoke-WebRequest -Uri "https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly-windows-x86_64.zip" -OutFile "lemline.zip"
Expand-Archive -Path "lemline.zip" -DestinationPath "."
.\bin\lemline.exe config create --help
```

</tab>
<tab id="java" title="macOS and other platforms (JAR)" group-key="java">

Install JDK 17 or later and download the nightly JAR:

```bash
curl -fL https://github.com/lemline/lemline/releases/download/nightly/lemline-nightly.jar -o lemline.jar
java -jar lemline.jar config create --help
```

</tab>
</tabs>

## 2. Configure the SQLite files

From `lemline-project`, run the configuration wizard. In PowerShell, use `.\bin\lemline.exe` for the remaining commands. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

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

<!-- workflow-example: data -->
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
bin/lemline listen --debug
```

The worker serves its metrics and health endpoints on port 8080. If it stops with `Port 8080 seems to be in use by another process`, start it with `--metrics-port 8081` instead, here and in the later exercises.

Leave the worker running and list the registered definitions from a second terminal in the same directory:

```bash
bin/lemline definition get
```

On a fresh project, expect `No workflows found.` Leave the worker running and continue with [Acknowledge the first order](lemline-tutorial-hello.md).
