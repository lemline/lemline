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
| POST /charges | orderId, paymentInfo.token (approved or declined) | paid, or HTTP 402 |
| POST /quotes/economy or /quotes/express | orderId, optional failTwice boolean | carrier and integer price (5 or 9); economy returns 503 on its first two attempts when requested |
| POST /shipments | orderId, carrier | orderId and carrier |
| POST /notifications | reference, recipient, message | Stored inbox entry |
| POST /reset | — | Fresh shop with 100 units and empty inbox |

A reservation belongs to one order. Use a fresh order ID for a new purchase. Duplicate successful writes return the existing business record. Reset only between exercises when no instances are running. The shop serves one process on one port; Lemline owns branching, retry timing, event routing and compensation.

## Verify the complete checkout and sensor journey

From this directory, run `node verify.mjs /absolute/path/to/lemline.jar` with Node.js 22.13 or later, Java and Docker Compose. Use a SQLite-capable Lemline JAR. Port 8787 must be free for the test's own container. The test creates a fresh SQLite project, registers the published YAML files, starts a worker, exercises both payments and both temperatures twice, and checks the stock, retries, shipments and inbox. Its temporary directory retains the command and worker logs.
