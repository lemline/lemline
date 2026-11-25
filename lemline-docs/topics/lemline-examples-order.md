# Shop checkout workflow

Run the example against the local Node.js shop by following [Reserve, charge and ship an order](lemline-tutorial-order-processing.md). Use the approved and declined inputs there to compare the final inventory, shipment and inbox records.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

The payment catch block releases the reservation identified in workflow context. Carrier retries happen after payment; a transient carrier failure retries the quote without charging the customer again. When both branches finish, the workflow compares their prices and passes the selected carrier to the shipment task.
