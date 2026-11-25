# Tutorial shop

Run `docker compose up -d` in this directory. Open http://127.0.0.1:8787 to inspect the stock, reservations, charges, quotes, shipments and inbox. Run `docker compose down` to discard this in-memory shop. For development use Node.js 22 or later: `node server.mjs` and `node --test`.

| Reader action | Lemline concept | Observable result |
|---|---|---|
| Submit an order ID | Input and set | The workflow returns an acknowledgement before any HTTP service is needed. |
| Order two units of SKU-1 | HTTP and context | Stock decreases and the workflow remembers the reservation ID. |
| Supply a declined token | Try/catch and compensation | Stock is restored; the order has no shipment. |
| Supply an approved token | Fork and retry | Both carriers quote; the economy carrier can fail twice before succeeding. |
| Compare prices and dispatch | Data transformation | The selected carrier appears on the shipment and in the customer inbox. |
| Publish payment and inventory events | Wait, correlation and fan-in | The SQLite worker resumes the waiting workflow. |
| Publish a warehouse reading | Event schedule and switch | Only temperatures above 80 produce a high-temperature event and a manager notification. |

## HTTP contract

Send JSON objects. Inspect all state with GET /state. Each POST below uses an orderId except warehouse notifications, which use a device ID as their reference.

| Method and path | Request | Response |
|---|---|---|
| POST /reservations | orderId, items (SKU-1 and positive integer quantity) | id, orderId, quantity, status |
| DELETE /reservations/{id} | — | Released reservation; repeated release restores stock only once |
| POST /charges | orderId, paymentInfo.token (approved, review or declined) | id, paid, status (approved or review) and amount (25 per unit), or HTTP 402 for declined. A repeated charge returns the first result |
| POST /quotes/economy or /quotes/express | orderId, optional failTwice boolean | carrier and integer price (5 or 9); economy returns 503 on its first two attempts when requested |
| POST /shipments | orderId, carrier | orderId and carrier |
| POST /notifications | reference, recipient, message | Stored inbox entry |
| POST /refunds | orderId of an approved charge | Refund for the charge amount; repeated calls return the same refund |
| DELETE /shipments/{orderId} | — | Shipment marked cancelled, so the reservation can be released |
| GET /stock (also /inventory/SKU-1) | — | sku, available units and number of active reservations |
| GET /old-stock | — | HTTP 301 to /stock |
| GET /openapi.json | — | OpenAPI 3.0 description of GET /products (listProducts) and GET /products/{productId} (getProduct) |
| GET /products | optional page, size (default 3), category, search | items, page, size, total, hasMore, nextPage |
| GET /products/{id} | — | Product with its price, or HTTP 404 for an unknown ID |
| GET /customers/{id} | — | C-100 and C-200 are valid customers; C-300 has an invalid email (emailValid false); others are HTTP 404 |
| POST /customers | name, email | Created customer (C-901…); HTTP 422 for an invalid email; same email returns the same customer |
| POST /email-checks | email | email and valid |
| POST /addresses/validate | address with street, city and two-letter country | valid true, or HTTP 422 naming the missing fields |
| GET /partner/stock | one credential: header X-API-Key, query api_key, Bearer token or Basic | Stock and authenticatedWith; HTTP 401 without credentials, 403 with a wrong value. Accepted values: key demo-api-key, token demo-token, user demo with password demo-password |
| POST /graphql | query and variables | Answers the customer(id) query and the addReview mutation only, with GraphQL-style data and errors |
| POST /documents, PATCH /documents/{id}, DELETE /documents/{id} | reference, title, optional content; PATCH: title, content, status or attachmentId | Draft document, updated document, document marked deleted |
| POST /audit | reference, message | Stored audit entry |
| GET /devices | optional zone, firmwareBelow | Warehouse devices: sensor-1 (cold-room), sensor-2 and sensor-3 (dock), with firmware, temperature and vibration |
| GET /devices/{id}, PATCH or PUT /devices/{id} | PATCH: status, firmware, config, settings, lastPing or zone | Device, or HTTP 404 for an unknown ID; updated device |
| POST /devices | deviceId, deviceType, optional location.zone and status | Registered device; the same ID returns the existing device |
| POST /devices/{id}/credentials | — | deviceId and a token |
| POST /devices/{id}/predictions | — | probabilityOfFailure from the device's vibration: 0.13, 0.78 and 0.42 for the three sensors |
| POST /energy-plans | zone | Average temperature of the zone's sensors, hvacSetpoint and lightingLevel |
| POST /reset | — | Fresh shop with 100 units and empty collections |

### Failures, delays and inspection on any route

Every route except GET /state and POST /reset accepts these query parameters:

| Parameter | Effect |
|---|---|
| fail | Answer with this status instead: 400, 401, 403, 404, 409, 422, 429, 500, 502, 503 or 504. 429 adds Retry-After: 1. |
| failTimes | Fail only the first N calls, then process the request normally. Without it, every call fails. |
| key | Count failures separately per key. Without it, the count uses the body's orderId or reference. |
| delayMs | Wait this many milliseconds, up to 60000, before answering. |

`/state` lists each simulated failure counter in `failures` and the last 500 requests in `requests`, with their method, path, query, headers, raw body and response status. Use them to check that a retry happened or that a credential was sent.

A reservation belongs to one order. Use a fresh order ID for a new purchase. Duplicate successful writes return the existing business record. Reset only between exercises when no instances are running. The shop serves one process on one port; Lemline owns branching, retry timing, event routing and compensation.

## Verify the complete checkout and sensor journey

From this directory, run `node verify.mjs /absolute/path/to/lemline.jar` with Node.js 22.13 or later, Java and Docker Compose. Use a SQLite-capable Lemline JAR. Port 8787 must be free for the test's own container. The test creates a fresh SQLite project, registers the published YAML files, starts a worker, exercises both payments and both temperatures twice, and checks the stock, retries, shipments and inbox. Its temporary directory retains the command and worker logs.

## Replay a documentation page

`node check-page.mjs /absolute/path/to/lemline.jar ../../lemline-docs/topics/<page>.md [--out <dir>]` follows one page as a reader would. It creates a fresh SQLite project with the commands of the setup tutorial, starts a worker with its own metrics port, then runs every `bash` block of the page in order. Before each `definition post -f NAME.yaml` it saves the workflow the page shows under that name. `bin/lemline` becomes `java -jar <jar>`, a `listen` command restarts the worker, and the shop is started and stopped through the page's own `docker compose` commands.

The results are compared with `expected/<page>.json`:

| Field | Meaning |
|---|---|
| instances | One entry per `instance start` of the page, in order: expected `type` (`workflowCompleted`, `workflowFaulted`) and a partial `output` or `error`. `null` skips one. A string starting with `~` is a regular expression. |
| others | Instances started by events, in completion order, with their `workflow`, `type` and `output`. |
| state | Partial match of the shop's `/state` before the shop stops or at the end. |
| `{"$contains": [...]}` | In place of an array: each listed item must match one of the actual items, in any order. |
| settleSeconds, timeoutSeconds | How long to wait for each instance before the next command (default 20) and for all of them at the end (default 240). |
| skip, allowFailure | Regular expressions of commands to skip or whose failure is expected. |
| pauseAfter | List of `{match, seconds}`: wait that long after a matching command, where the page tells the reader to wait. |

Instances beyond those listed in `instances`, or all of them without that list, must complete. The output directory keeps `commands.log`, `results.json` and the compressed worker logs. Run it outside any sandbox that blocks Docker.
