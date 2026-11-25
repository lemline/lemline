---
title: How to run scripts or containers
---

# How to run scripts or containers

Open Workflow defines a `run` task for shell commands, scripts, containers and child
workflows. See [Supported Features](lemline-ref-supported-features.md) for Lemline's
runtime support for each variant.

## Prepare the examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The script
example needs Python 3.8 or later on the worker's machine (`python3` on the path, or `LEMLINE_PYTHON_EXEC` set).

## Run an inline script

Put the script in `run.script.code`, set `language` (`python` or `js`) and pass inputs as named `arguments`.
Lemline writes the code to a temporary file and starts it with each argument as a name followed by its value, so the
script below reads `sys.argv` as pairs. With `return: stdout`, the task output is what the script prints.

<code-block lang="yaml" src="workflows/reference/inline-email-check.yaml"/>

Save it as `inline-email-check.yaml`:

```bash
bin/lemline definition post -f inline-email-check.yaml
bin/lemline instance start examples inline-email-check --input '{"email":"ada@example.com"}'
bin/lemline instance start examples inline-email-check --input '{"email":"not-an-email"}'
```

The instances complete with the printed line as a string, `"valid"` and then `"invalid"`.

## Load an external script

Use `run.script.source.endpoint` to reference a script instead of inlining it. Lemline reads the script from the file
system of the runner that executes the task: use an absolute `file://` URI and place the script at that path on every
runner. This example reads it from `/tmp/lemline-scripts`; on Windows, use a path such as `file:///C:/lemline-scripts/email-validator.py`.

Create the script with the same email check as above:

```bash
mkdir -p /tmp/lemline-scripts
cat > /tmp/lemline-scripts/email-validator.py <<'EOF'
import re
import sys
args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
valid = re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", args["email"])
print("valid" if valid else "invalid")
EOF
```

<code-block lang="yaml" src="workflows/reference/load-external-script.yaml"/>

Save it as `load-external-script.yaml`:

```bash
bin/lemline definition post -f load-external-script.yaml
bin/lemline instance start examples load-external-script --input '{"email":"ada@example.com"}'
bin/lemline instance start examples load-external-script --input '{"email":"not-an-email"}'
```

The instances complete with `valid` and `invalid`. A `source.endpoint` that is not a `file://` URI faults with
`Unsupported scheme for script source`.

The endpoint is either a plain string or an object with a `uri` property. Both accept a
runtime expression, for example `endpoint: ${ .scriptUri }`.

The runner needs Node.js 22 or later for `js` scripts and Python 3.8 or later for `python`
scripts.


## Run a container

Set `run.container.image` and, if needed, a `command` and `volumes`.
The referenced volume path has to be available to the executor.

<code-block lang="yaml" src="workflows/reference/run-container-with-volume.yaml"/>

> **Runtime support:** Lemline accepts this definition but has no container executor yet. The task fails with
> `Unknown run task type: RunContainer`. Use an inline script, or call a service over HTTP, instead.

Save it as `run-container-with-volume.yaml` to see the failure:

```bash
bin/lemline definition post -f run-container-with-volume.yaml
bin/lemline instance start examples run-container-with-volume
```

## Invoke a child workflow

Identify the child by namespace, name and version. Use `run.workflow.input`
to supply named values for the child workflow. Register the child definition before
starting the parent. This child asks the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop)
for an economy quote. Start the shop from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Save the child as `shipping-quote.yaml`:

<code-block lang="yaml" src="workflows/reference/shipping-quote.yaml"/>

The parent waits for the child and receives its output. Save it as `invoke-order-workflow.yaml`:

<code-block lang="yaml" src="workflows/reference/invoke-order-workflow.yaml"/>

```bash
bin/lemline definition post -f shipping-quote.yaml
bin/lemline definition post -f invoke-order-workflow.yaml
bin/lemline instance start examples invoke-order-workflow --input '{"orderId":"ORD-42"}'
```

The parent completes with the child's quote, `{"quote":{"orderId":"ORD-42","carrier":"economy","price":5}}`, and
the shop lists one economy quote for `ORD-42`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See [Run Workflow](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#workflow-process) for awaiting completion and handling results.
