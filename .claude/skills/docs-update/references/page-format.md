# Page format for runnable examples

Reference model: `lemline-docs/topics/lemline-ref-http.md` (16 runnable examples, snippets in
`lemline-docs/snippets/workflows/lemline-ref-http/`) and `lemline-docs/topics/lemline-howto-retry.md`.
New snippets go in `lemline-docs/snippets/workflows/<page-name>/`.

## Example in the body

```markdown
### Retry a failing call

The workflow retries the stock call twice, then reads the stock.

<code-block lang="yaml" src="workflows/lemline-ref-http/retry-behavior.yaml"/>
```

The sentence before the block says what the example shows; the result goes in the table of the run
section, not in prose that may drift.

## Run section

Put it near the end, before "Related Resources":

````markdown
## Run the Examples

Every example on this page is a complete workflow that calls the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). Complete
[the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and start the shop
from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

From a second terminal in the setup directory, register and start each example:

```bash
bin/lemline definition post -f retry-behavior.yaml
bin/lemline instance start examples retry-behavior --input '{"sku":"SKU-1"}'
```

| Example | Expected result |
|---|---|
| `retry-behavior` | The stock after two retries |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
````

Rules the replays depend on:

- `definition post -f NAME.yaml`: NAME is the base name of an included snippet of the page; the
  replays save that snippet under this name.
- One `instance start` per expected result, in the order of the table.
- Inputs inline with `--input '…'` (single quotes).
- Environment the worker needs (secrets): `export` lines followed by `bin/lemline listen --debug`
  in the same block, with a sentence telling the reader to stop the setup worker first.
- Events: the page sends them by starting a small emitting workflow (`emit-*.yaml`) after the
  instance that listens, with a sentence "wait until the worker logs that the instance is waiting"
  when the timing matters (the tool replay needs a matching `pauseAfter`).
- Results the reader checks elsewhere (shop state, metrics): say where, e.g.
  `curl -s http://127.0.0.1:8787/state`.
- Pages without the shop (pure data, jq, timers) keep the same section without the compose lines.

## `docs.tree` pages

Same sections. Links to the setup and to the shop must resolve inside the `docs` instance (see
SKILL.md step 3). Lemline-specific behavior goes in a `> **Lemline:** …` note.
