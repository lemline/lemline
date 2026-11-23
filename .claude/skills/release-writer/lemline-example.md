# Example: the published Lemline 0.7.0 release notes

This is the reference for structure, tone and level of detail. It is the text of the [v0.7.0 GitHub release](https://github.com/lemline/lemline/releases/tag/v0.7.0), unchanged.

---

## Summary

Lemline 0.7.0 lets workflows react to the outside world and lets one deployment serve several teams safely.

- Workflows can **start by themselves when events arrive**, instead of being started by your code.
- Workflows can **use secrets** (API keys, tokens) kept on the runner, never stored or sent in messages.
- You can **add the same steps around many tasks** (audit, logging, test mocks) without editing each task.
- Each runner can be **dedicated to some namespaces**, so teams or environments sharing a broker and a database never see each other's work.
- The **dashboard is redesigned**, with dedicated pages, better search and clearer timelines.

> **Before you upgrade from 0.6.x**
>
> 1. **Stop or drain every 0.6.x runner before starting 0.7.0 runners.** The two versions cannot work side by side, and messages are now routed per namespace (`lemline-<namespace>-commands`, …). If you create topics, queues or ACLs yourself, create the new ones first.
> 2. **Validate your stored workflow definitions.** Some 0.6.x syntax is no longer accepted: shell `arguments` are a list (`[Hello, World]`), static HTTP query values must be quoted (`page: '1'`), error filters use `catch.errors.with.detail`, and `for.in` / `while` need `${ }` unless the workflow uses `evaluate.mode: loose`. See the [migration guide](https://github.com/lemline/lemline/blob/main/docs/sdk-java-upgrade.md).
> 3. **Update consumers of lifecycle events.** Their types are now `org.open-workflow-specification.workflow.*.v1` and `…task.*.v1`. The gateway and dashboard still read events stored with the old types.

---

## New Features

### Start a workflow when events arrive

Declare `schedule.on` and Lemline starts a new instance as soon as matching CloudEvents arrive. The workflow receives those events as its input.

- **`one`** / **`any`**: start on a single matching event.
- **`all`**: wait until every listed event has arrived, optionally only those sharing a key (the same order id, for example).
- **`until`**: collect events until a condition is true, then start once with the whole batch.

The same event delivered twice never starts two instances.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: low-heartbeat-alert
  version: '0.1.0'
schedule:
  on:
    one:
      with:
        type: com.example.hospital.events.patients.heartbeat.low
  read: envelope   # pass full CloudEvents (default: data only)
do:
  - callNurse:
      call: http
      with:
        method: post
        endpoint: https://hospital.example.com/api/v1/notify
        body:
          patientId: ${ $workflow.input[0].data.patient.id }
```

### Use secrets in workflows

List the secrets a workflow needs in `use.secrets`. The runner reads them from its environment variables, and expressions read them as `$secrets`. Their values are never written to the database or sent through the broker.

```yaml
use:
  secrets:
    - paymentApiKey
do:
  - charge:
      call: http
      with:
        method: post
        endpoint: https://payments.example.com/charges
        headers:
          Authorization: '${ "Bearer " + $secrets.paymentApiKey }'
```

```bash
export paymentApiKey='sk_live_…'
```

If a declared secret is missing, the task fails with an authorization error. A child workflow only gets the secrets it declares itself.

### Add steps around many tasks at once

Extensions (`use.extensions`) run `before` and `after` steps around every task that matches a condition: record every HTTP call, log each step, or replace calls with fake answers in tests.

```yaml
use:
  extensions:
    - audit:
        extend: call
        when: '${ $task.definition.call == "http" }'
        before:
          - recordRequest:
              set:
                reference: '${ $task.reference }'
```

Waits, retries and listeners inside extensions survive restarts like any other task, and the dashboard shows extension steps on the timeline.

### Dedicate runners to namespaces

Each runner declares the namespaces it serves, and only runs and stores workflows of those namespaces. You can run `acme-prod` and `acme-staging` on separate runner pools sharing one broker and one PostgreSQL, and grant broker permissions per namespace.

```yaml
lemline:
  namespaces: acme-prod, acme-staging   # absent: "default"
```

- A message that reaches the wrong runner is set aside, not executed. Once the configuration is fixed, `lemline failures replay` sends it again.
- The gateway warns when you start a workflow in a namespace that no runner serves.

See the [namespace isolation guide](https://github.com/lemline/lemline/blob/main/docs/namespace-isolation.md).

### A redesigned dashboard

- Dedicated pages for instances and definitions, a namespace picker, and layouts that work on a phone.
- Better instance search and filters, clearer statuses and errors.
- Clearer timelines: long waits take less room, loop iterations no longer look like duplicates, and failed tasks show where they stopped.
- A **Settings** page shows every gateway setting, its value and where it comes from, with secrets hidden.
- Old dashboard links still work.

### More possibilities in workflow definitions

- **Loose expressions**: with `evaluate.mode: loose`, a value can be written without `${ }`. If it is not a valid expression, it is used as plain text.
- **Named errors**: `raise.error` can reuse an error declared in `use.errors`, and `catch` can match it by its type. Error `title` and `detail` can be computed.
- **Computed durations**: wait durations, listen timeouts, and retry delays and limits can be computed from data.
- **`catch.then`** chooses where the workflow continues after a caught error.
- **`shell.stdin`** sends text to a shell command.
- **`$workflow.definition`** gives expressions access to the workflow's own definition.

---

## Improvements

- **Faster starts from the gateway**: a workflow started through the gateway begins right away, instead of waiting up to the next database check.
- **Faster event handling**: workflows waiting for events, and workflows started by events, need fewer database queries.
- **Faster workflow execution**: the engine spends less time converting data during expressions and validation.
- **Closer to the specification**: Lemline follows the Open Workflow Specification Java SDK 7.35.3, so definitions written for other compliant runtimes behave the same way.
- **Real waiting time on timelines**: an instance's start time is now when a runner picked it up, so time spent waiting in the queue is visible.

---

## Bug Fixes

- **HTTP calls**: expressions in the request `body` and in `basic` / `bearer` authentication are now evaluated. Before, they were sent as literal `${ … }` text, so a body or a token built from data or `$secrets` did not work.
- **Scheduled workflows**: every cron, `every` and `after` run now appears in the gateway and the dashboard, with its own start time.
- **CLI**: instances started with `lemline instance start` now appear in the instance list. `lemline --help` and `--version` work without a configuration file or a database.
- **Namespaces**: `lemline.namespaces: acme-prod, acme-staging` (with spaces) no longer stops the runner from starting. A runner no longer takes events meant for another namespace's runner.
- **PGMQ**: a message that cannot be read no longer blocks its queue; it goes to the dead letter queue. A failing queue no longer holds up the others.
- **RabbitMQ**: the first workflow started right after startup is no longer lost, and the runner no longer reconnects after shutting down.
- **Duplicate events**: an event delivered twice is no longer counted twice by a listener.
- **Remote functions**: one function that cannot be loaded no longer blocks all events of its namespace.
- **Event-triggered workflows**: updating a definition no longer leaves the old version running when another definition changes at the same time.
- **Extensions**: `catch` and `retry` conditions can read `$task`; before, such a condition failed the workflow.
- **Timelines**: failed tasks and fork branches now show their end.
- **Dashboard**: starting a cron workflow shows a confirmation with a link to its instances. The live indicator is hidden on finished instances. Counters and lists keep updating while the page is open, and no update is lost after a reconnection.

---

**Full changelog**: https://github.com/lemline/lemline/compare/v0.6.3...v0.7.0



---

# What makes it work

- **Four sections only.** Summary, New Features, Improvements, Bug Fixes; no Dependencies or Breaking Changes section.
- **Upgrade actions cannot be missed.** They sit in the Summary, numbered, each starting with what to do.
- **Features are titled by what they let users do** ("Start a workflow when events arrive"), and only the main ones carry an example.
- **Effects, not mechanisms.** "The same event delivered twice never starts two instances", not how deduplication works.
- **Bug fixes describe the symptom** users had before, grouped by component.
