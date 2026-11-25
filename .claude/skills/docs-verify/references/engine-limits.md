# Engine limits that pages must state

These change as the engine evolves. Refresh before relying on them:

```bash
gh issue list --repo lemline/lemline --state open --limit 50
gh issue list --repo lemline/lemline --state closed --label bug --limit 20
```

When an issue closes, the pages that mention it must be updated (docs-update skill) and replayed with
a JAR that contains the fix.

## Not executed (registration only)

| Feature | Behavior at `instance start` |
|---|---|
| `call: openapi` | fault: unknown task type `CallOpenAPI` (#45) |
| `call: grpc` | fault: unknown task type `CallGRPC` |
| `call: asyncapi` | fault: unknown task type |
| `run: container` | fault: unknown task type `RunContainer` |
| OAuth 2.0 / OIDC authentication | fault: unsupported authentication, no request sent |

## Known differences (check the issue state)

| Topic | Behavior | Issue |
|---|---|---|
| HTTP task `timeout` | not enforced; the client's 30 s limit applies | #96 |

## Fixed defects found by verification (pages now describe the fixed behavior)

#97 (failed HTTP call as a `communication` error with the HTTP status), #98 (workflow `input.from`),
#99 (emit with object data), #100 (`run.script` from `source.endpoint`), #104 (`then: end` and
`output.as`), #107 (`definition post` of an unparsable file), #108 (YAML mock bodies), #109
(`definition get` without a terminal), #110 (jq `todate`), #111 (`use.catalogs`), #112 (redirects
only with `redirect: true`). Replays on a nightly built before these fixes will disagree with the
pages: check the JAR version first.

## Filing a new issue

```bash
gh issue create --repo lemline/lemline --label bug --title "<what goes wrong, in user terms>" --body-file issue.md
```

Body: the smallest workflow, the commands, actual result, expected result with the DSL specification
section, and the JAR version from the worker log. Check open issues first and ask the user before
filing.
