# Shop checkout workflow

Run the example against the local Node.js shop by following [Reserve, charge and ship an order](lemline-tutorial-order-processing.md). Use the approved and declined inputs there to compare the final inventory, shipment and inbox records.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Start the shop from the root of
the Lemline source checkout, then run the other commands from a second terminal in the setup directory (with the JAR,
substitute `java -jar lemline.jar` for `bin/lemline`):

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Save this definition as `compensate-reservation.yaml`:

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

```bash
bin/lemline definition post -f compensate-reservation.yaml
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-201","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"approved"},"failTwice":true}'
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-202","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"declined"}}'
```

Open [the shop](http://127.0.0.1:8787): 98 units remain, ORD-201 is shipped with economy after two failed economy
quotes, and ORD-202 is released with no shipment. The second instance faults with `Order completion failed`.

The payment catch block releases the reservation identified in workflow context. Carrier retries happen after payment; a transient carrier failure retries the quote without charging the customer again. When both branches finish, the workflow compares their prices and passes the selected carrier to the shipment task.

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
