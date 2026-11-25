---
name: docs-update
description: >-
  Rewrite Lemline documentation pages so every example a reader sees can be run as shown and gives
  the result the page announces: replace fragments and fictitious hosts with complete workflows on
  the local tutorial shop, add the "Run the Examples" section and its expected-results table, extend
  the shop when an example needs a route it lacks, write the replay expectations, then verify with
  the docs-verify skill. Works on both Writerside instances (lemline.tree and docs.tree, which share
  topics and snippets). Trigger it on "mets à jour la page X", "rends les exemples exécutables",
  "passe docs.tree sur la boutique", "la doc ne correspond plus au moteur", "documente le
  correctif #N", after an engine change that pages describe, or when docs-verify found a wrong page.
  Do NOT use it only to check pages (docs-verify), to write new conceptual content without
  examples, or to change engine behavior.
---

# Update documentation pages

Goal: a reader who follows the page from the SQLite setup gets, for every example, the result the
page shows. Instances, folders and scripts are described in the docs-verify skill; read its
`SKILL.md` and `references/pitfalls.md` first.

## Procedure, one page at a time

### 1. Know what the page uses

```bash
python3 .claude/skills/docs-verify/scripts/doc_pages.py lemline-docs/<instance>.tree
python3 .claude/skills/docs-verify/scripts/doc_pages.py --users workflows/<folder>/<snippet>.yaml
```

A snippet can be included by pages of **both** instances. Before changing one, list its users: a
change must stay right for each of them, otherwise create a new snippet for this page.

### 2. Make every shown example runnable

- Include each runnable example **whole** (`<code-block lang="yaml" src="…"/>`, no `include-lines`).
  A fragment may stay only to explain one field, right next to the full example it comes from.
- Every snippet is a complete workflow: `document` with `dsl: '1.0.3'`, `namespace: examples` (or
  `tutorial` for tutorial pages), a unique `name` equal to the file name, `version`.
- Replace fictitious hosts (`api.example.com`, petstore, swapi…) with the tutorial shop at
  `http://127.0.0.1:8787`. Keep the example's intent: the shop has customers, products, stock,
  orders, payments, shipments, refunds, documents, audit, partner credentials and failure controls.
  See `references/shop.md`.
- Features the engine does not execute (`references/engine-limits.md` of docs-verify) stay as
  examples, but the page says plainly that Lemline accepts them and the instance faults, and offers
  the runnable alternative when one exists.
- `docs.tree` pages describe the DSL; keep their wording engine-neutral and put Lemline specifics
  (registration only, limits) in a note.

### 3. Add the run section

Use the format in `references/page-format.md`: prerequisites with a link to the setup, the shop
start command, the register-and-start commands with the exact inputs, an expected-results table,
the shop stop command. The commands are what the replays execute: anything the reader must do
(export a secret, restart the worker, wait for an event listener, send an event) is a command or an
explicit sentence, never implied.

A link from a `docs.tree` page to a page that is only in `lemline.tree` may not resolve in the
`docs` instance (`source_check.py` warns; confirm with `writerside_build.sh docs` and
`render_check.py` the first time). For the setup, either add the setup page to `docs.tree` or ask
the user which published URL to use.

### 4. Extend the shop only when needed

When no route fits, add one to `examples/tutorial-services/server.mjs`, document it in
`examples/tutorial-services/README.md` ("HTTP contract"), and keep the rules of
`references/shop.md`. Restart the shop container to load the change.

### 5. Write the expectations

Create or update `examples/tutorial-services/expected/<page>.json` from the expected-results table
of the page, never from a run. Field reference: `examples/tutorial-services/README.md` ("Replay a
documentation page").

### 6. Verify

Run the docs-verify procedure on the page: static check of the instance, both replays, read every
difference. Also replay the other pages that include a snippet you changed. Iterate until the page
does what it says, or until the only differences are engine defects filed as issues and stated on
the page.

### 7. Commit (only when the user asks)

One commit per page: the page, its new or changed snippets, its expectation file, and any shop
change it needed. Message: `Make <page title> examples runnable against the tutorial shop` or the
actual change. Mention the verification level and JAR version in the body.

## Rules

- Do not modify the engine (`lemline-core`, `lemline-runner*`). An engine defect becomes an issue
  (docs-verify `references/engine-limits.md`), and the page states the current behavior.
- Do not show results you did not observe. If a run cannot be done, the page says what the example
  does and the report says it was not run.
- Keep pages in English, in the existing voice: explain what the reader sees first.
- After an engine fix, update the pages that described the old behavior and replay them with a JAR
  that contains the fix.
