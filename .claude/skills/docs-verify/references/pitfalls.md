# Pitfalls met while verifying the documentation

Read this before deciding that a page or the engine is wrong.

## Timing

- An emitted event only resumes instances that are **already listening**. When a page emits events in
  a loop or right after `instance start`, the reader needs a pause; the tool needs `pauseAfter`.
  About 10 seconds between events is safe.
- Waits, retry delays and listen timeouts are picked up by a background scan every 10 seconds on
  SQLite: a 5-second wait finishes after 5 to 15 seconds. Set `settleSeconds` accordingly.
- A workflow with `schedule.on` (event-started) must be registered **before** the worker starts, or
  the worker must be restarted after `definition post`.
- `schedule.cron` runs cannot be awaited in a replay: verify the registration only.

## Inputs and expectations

- A `catch` with `as: failure` exposes `$failure`, not `$error`. Examples that read `$error` after an
  `as:` silently produce `null`.
- When the CLI refuses an `instance start` (input schema, missing input), no instance exists: the tool
  must not wait for it (put the command in `allowFailure` and a `null` in `instances`).
- Expectations are partial matches: list only what the page announces, not the whole output.
- `$contains` for arrays whose order the page does not promise (fork results, event-started
  instances).
- Results come from `analytics.db` (`lemline_lifecycle_events`), which exists only once the worker
  started; an empty result right after start is not a failure.

## Shell replay

- `export X=value   # comment` must keep only the value: replay_shell evaluates exports with bash.
- `definition get` without arguments used to loop without a terminal (#109, fixed); any command that
  waits for a terminal blocks the replay: run it with input closed or skip it.
- In shell loops, `pgrep -f pattern` matches its own command line and never ends.
- Two workers on the same metrics port collide: always pass `--metrics-port`.
- `localhost:8080` in a page (health, metrics) is the reader's metrics port: the scripts rewrite it.

## Pages

- A partial inclusion (`include-lines`) shows a fragment a reader cannot run. Runnable examples are
  included whole; a fragment is only for explaining a field next to a full example.
- Snippets on fictitious hosts (`api.example.com`, petstore, swapi) cannot be verified: move them to
  the tutorial shop (docs-update skill) or mark them as illustration only.
- `docs.tree` pages link to some pages that are only in `lemline.tree` (source_check.py warns): they
  may not resolve in the `docs` site. Not yet confirmed by a `docs` build; record the outcome here.

## Writerside

- The render comparison strips HTML tags from the built page only, never from the snippet source.
- A stale `webHelp*-all.zip` reports every snippet changed since its build: rebuild first.
- The builder image is several GB; disk space runs out quickly with Kafka and other images present.
