# The tutorial shop

Source: `examples/tutorial-services/server.mjs` (Node, no dependencies), started by
`examples/tutorial-services/compose.yaml` on `http://127.0.0.1:8787`. Its route list is the "HTTP
contract" of `examples/tutorial-services/README.md`; read it before choosing or adding a route.

## What already exists

- Business data: customers (`C-100` Ada Lovelace, `C-200` Alan Turing…), products and categories,
  stock per SKU, orders, payments (a `review` token for manual review), shipments (create, delete),
  refunds, documents, audit entries, email and address checks.
- Integration stand-ins: a GraphQL endpoint, `/openapi.json`, a 301 redirect (`/old-stock`), a
  partner route with credentials (API key `demo-api-key`, bearer `demo-token`, basic
  `demo:demo-password`), devices, predictions and energy plans for the IoT examples.
- Controls on every route, as query parameters: `fail=<status>`, `failTimes=<n>` (fail the first n
  calls for that `key`), `key=<id>`, `delayMs=<ms>` (at most 60000).
- Inspection: `GET /state` returns the recorded `requests` and `failures`; `POST /reset` clears them.

## Rules for new routes

- Deterministic: the same request gives the same response, except where a control asks otherwise.
- Data a reader can see in the page: return small JSON with readable ids (`C-…`, `SKU-…`, `O-…`).
- Every new route accepts the shared controls and is recorded in `/state`.
- Credentials stay the demo ones; no real secret, no outbound network call.
- Document the route in the README table in the same commit, and keep the existing routes
  backward compatible: other pages' expectations rely on them.
- The container mounts `server.mjs` read-only: restart it after a change (`docker compose -f
  examples/tutorial-services/compose.yaml restart`), then replay every page that uses the route
  (`grep -rl '<route>' lemline-docs/snippets`).
