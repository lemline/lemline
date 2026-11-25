# Reserve, charge and ship an order

## Start the tutorial shop

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep the worker running on the host. In a separate terminal, open the root of the [Lemline source checkout](https://github.com/lemline/lemline) matching these docs (the directory containing `examples/tutorial-services`). With Docker Compose running, start the single shop container:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Open [the shop](http://127.0.0.1:8787). The stock starts at 100 units of SKU-1. To repeat this exercise, wait for outstanding workflows to finish, then reset the tutorial shop:

```bash
curl -X POST http://127.0.0.1:8787/reset
```

## Register checkout

From the setup directory, save this definition as `compensate-reservation.yaml`. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The workflow sends real HTTP requests to the local shop.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

```bash
bin/lemline definition post -f compensate-reservation.yaml
```

The reservation response replaces the task input. The workflow exports its ID to context for compensation and reads the original payment details from `$workflow.input`.

## Ship a paid order

```bash
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-201","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"approved"},"failTwice":true}'
```

Refresh the shop and filter by `ORD-201`. Expect 98 units in stock and one reserved record. The fork requests both quotes. Express succeeds once at 9, while economy returns 503 twice before succeeding at 5. The workflow compares the prices, books economy and writes one customer confirmation to the inbox. The order of the first two carrier requests can vary.

## Compensate a declined payment

```bash
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-202","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"declined"}}'
```

Filter by `ORD-202`. The reservation is released, the charge is declined, and shipments and inbox are empty for this order. Total available stock returns to 98. The workflow raises `Order completion failed` after the release. Repeat both paths from a reset shop with the same inputs.

Continue with [Wait for payments and collect order checks](lemline-tutorial-waits.md).
