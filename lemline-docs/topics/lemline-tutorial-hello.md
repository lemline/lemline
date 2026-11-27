# Acknowledge the first order

<web-summary>Build an order acknowledgement workflow, pass data between tasks, and run it with the Lemline CLI.</web-summary>

Complete [the SQLite setup](lemline-tutorial-setup.md). Keep the worker running and use a second terminal in the setup directory. Substitute `java -jar lemline.jar` for `bin/lemline` if using the JAR. Each definition below is a new version of the same workflow. Save each in turn as `acknowledge-order.yaml`.

## Return an acknowledgement

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/1-creating-your-first-workflow.yaml"/>

```bash
bin/lemline definition post -f acknowledge-order.yaml
bin/lemline instance start tutorial acknowledge-order 0.1.0
```

The CLI prints the instance ID. In the worker terminal, find that ID and the line `Workflow completed with output`. The worker completes the workflow with `{"message":"Order received"}`.

## Pass data between tasks

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/4-enhancing-your-workflow.yaml"/>

```bash
bin/lemline definition post -f acknowledge-order.yaml
bin/lemline instance start tutorial acknowledge-order 0.1.1
```

The second task reads the first task's output and returns `Order received for ORD-100` in the message.

## Accept the customer's order ID

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/5-adding-user-input.yaml"/>

```bash
bin/lemline definition post -f acknowledge-order.yaml
bin/lemline instance start tutorial acknowledge-order 0.1.2 --input '{"orderId":"ORD-101"}'
```

The message now contains the ID supplied at startup.

## Add conditional logic

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/6-adding-conditional-logic.yaml"/>

```bash
bin/lemline definition post -f acknowledge-order.yaml
bin/lemline instance start tutorial acknowledge-order 0.1.3 --input '{"orderId":"ORD-101","hour":10}'
bin/lemline instance start tutorial acknowledge-order 0.1.3 --input '{"orderId":"ORD-102","hour":15}'
bin/lemline instance start tutorial acknowledge-order 0.1.3 --input '{"orderId":"ORD-103","hour":20}'
```

Expect messages starting with `Good morning`, `Good afternoon` and `Good evening`, respectively. The switch enters one branch. The morning and afternoon tasks jump to `createMessage` to skip subsequent greetings. The evening task reaches it as the next task in sequence.

Continue with [Reserve, charge and ship an order](lemline-tutorial-order-processing.md).
