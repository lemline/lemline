---
name: docs-verify
description: >-
  Verify that the Lemline documentation does what it says: replay every runnable example of a
  Writerside page (or of a whole instance, lemline.tree or docs.tree) against a real Lemline JAR and
  the local tutorial shop, compare each result with what the page announces, check links, anchors
  and snippet inclusions in the sources and in the site Writerside builds, and write a verification
  table with logs. Trigger it on "vérifie la doc", "vérifie la page X", "rejoue les exemples",
  "est-ce que la doc est à jour avec le nightly", "verify docs.tree", "check the documentation
  against the engine", after an engine fix that a page documents, or before a release. Do NOT use it
  to rewrite pages or move examples onto the shop (that is docs-update, which calls this skill at
  the end), nor to fix the engine: a verified engine defect becomes a GitHub issue, not a code change.
---

# Verify the documentation

The documentation lives in `lemline-docs/`. Two Writerside instances share its `topics/` and
`snippets/` folders:

| Instance id | Tree file | Content |
|---|---|---|
| `lemline` | `lemline-docs/lemline.tree` | The Lemline site: tutorials, how-tos, references, examples (published by `build-docs.yml`) |
| `docs` | `lemline-docs/docs.tree` | "Open Workflow DSL": the `dsl-*` pages and shared introduction pages |

Every script takes the tree file (or the instance id for the Writerside build), so the same steps
verify either instance. Run everything from the repository root.

## What "verified" means

A page is verified when each example it shows was run as the page tells a reader to run it, and gave
the result the page announces. Record one level per page:

- **Real**: the workflow ran against the engine and the tutorial shop (or needed no service).
- **Simulated**: the run used `--mock-config`; say which calls were simulated.
- **Registration only**: the engine has no executor for it (OpenAPI, gRPC, AsyncAPI, containers,
  OAuth 2.0…): `definition post` accepted it, `instance start` faults with an "unknown task type" or
  "unsupported" error. The page must say so; check that it does.
- **Checked against …**: reference pages without workflows (CLI options, config keys, metrics,
  analytics columns), compared with `--help`, `config show`, `/q/metrics`, `analytics.db`.
- **Not run**, with the missing prerequisite.

Two replays per page, because they catch different defects:

1. **Tool replay** (`examples/tutorial-services/check-page.mjs`): follows the page with expectations
   from `examples/tutorial-services/expected/<page>.json` and fails on any difference.
2. **Shell replay** (`scripts/replay_shell.py`): runs the page's commands as copied into a shell,
   without expectations or extra waits. It catches pages that only work with the tool's waits, wrong
   quoting, missing `export`s, commands in the wrong order. You read its `outcomes.txt` against the
   page yourself.

## Procedure

### 1. Inventory

```bash
python3 .claude/skills/docs-verify/scripts/doc_pages.py lemline-docs/<instance>.tree
```

The table lists, per page, its inclusions, partial inclusions (`include-lines`), shell blocks,
snippets on fictitious hosts, snippets on the shop, and whether it has run commands. Pages with
partial inclusions, fictitious hosts or no run commands cannot be verified as they are: hand them to
the docs-update skill first, or record them as not runnable. `docs.tree` has many such pages.

### 2. Static checks (no engine needed)

```bash
python3 .claude/skills/docs-verify/scripts/source_check.py lemline-docs/<instance>.tree
```

Errors: missing pages, anchors, snippets, `include-lines` past the end of a snippet. Warnings: links
to a page that exists but is not in this instance; they may not resolve in this instance's site
(confirm with the build of step 6).

### 3. Get the JAR to test

Use the version the documentation targets, usually the latest nightly (`gh release list --repo
lemline/lemline`), or a local build (`./gradlew :lemline-runner:quarkusBuild` produces the runner
JAR). Keep a single copy outside the repository. Record the exact version: every worker log prints
it on its startup line (`lemline <version> on JVM`), and the report reads it from there. An engine
fix merged after the nightly was built is not in it: check the nightly date against the merge.

### 4. Replay the pages

```bash
python3 .claude/skills/docs-verify/scripts/run_pages.py <jar> --run build/reports/docs-verify/<run-name> <pages...>
python3 .claude/skills/docs-verify/scripts/run_pages.py <jar> --run build/reports/docs-verify/<run-name> --tree lemline-docs/<instance>.tree
```

Needs Docker (the shop), Node 22.5+ (`node:sqlite`) and Java 17+. Run it outside the sandbox, in the
background for more than a couple of pages: a page takes from one to ten minutes. Pages run one at a
time because they share the shop's port 8787. Each worker gets its own free metrics port; never
touch another process holding port 8080. Stop stray workers with SIGTERM only.

A page with no `expected/<page>.json` is replayed with the default expectation (every instance
completes). Write the expectation file from what the page announces, never from what the run
produced; see the field list in `examples/tutorial-services/README.md` ("Replay a documentation
page"). Single steps: `node examples/tutorial-services/check-page.mjs <jar> <page.md> --out DIR` and
`python3 .claude/skills/docs-verify/scripts/replay_shell.py <jar> <page.md> --out DIR`.

### 5. Read every difference before concluding

For each failure, open `tool/commands.log` and the worker log, then decide which of these it is:

1. **My expectation or my input is wrong** (most frequent): fix the expectation file and replay.
2. **The page is wrong** (wrong output shown, missing step, wrong command, example that contradicts
   the text): fix the page with the docs-update skill, then replay.
3. **The engine is wrong** (the page follows the DSL specification and the engine departs from it):
   reproduce it with the smallest workflow, file an issue (see `references/engine-limits.md`), and
   make the page state the current behavior with a link to the issue. Do not change the engine.
4. **The environment** (port taken, image missing, disk full, timing): fix and replay, or record
   "Not run" with the reason.

`references/pitfalls.md` lists the traps already met; read it before diagnosing.

### 6. Check the built site

```bash
.claude/skills/docs-verify/scripts/writerside_build.sh <instance-id> build/reports/docs-verify/<run-name>/writerside [--remove-image]
python3 .claude/skills/docs-verify/scripts/render_check.py build/reports/docs-verify/<run-name>/writerside lemline-docs/<instance>.tree
```

The build uses the Writerside builder image and version of `.github/workflows/build-docs.yml`
(several GB: ask before downloading it, offer `--remove-image`). The render check reads Writerside's
own `report.json`, then checks every HTML link and anchor and that each included snippet appears in
full in its page. Build from the current sources: an older archive reports every snippet changed
since as a mismatch.

### 7. Report

Write `notes.json` (`{"<page>": {"level", "observed", "unverified"}}`) from what you read in the
logs, then:

```bash
python3 .claude/skills/docs-verify/scripts/report.py --run build/reports/docs-verify/<run-name> --tree lemline-docs/<instance>.tree --notes notes.json > build/reports/docs-verify/<run-name>/verification.md
```

Keep the run folder: logs are evidence. `build/` is not committed. Tell the user, in plain words,
which pages do what they say, which ones were corrected, which engine defects were filed, and what
could not be verified and why.

## Rules

- Never edit the engine (`lemline-core`, `lemline-runner*`) to make a page pass.
- Never weaken an expectation to match a run: the expectation states what the page promises.
- Engine issues: one issue per defect, with the smallest reproducing workflow, the command, the
  actual and the expected behavior, and the JAR version. Check open issues first.
- Commit only when the user asks: one commit per page when pages change, expectation files together
  with their page.
