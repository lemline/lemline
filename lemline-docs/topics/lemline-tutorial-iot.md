# Monitor the warehouse temperature

## Start the tutorial shop

Complete [the SQLite setup](lemline-tutorial-setup.md) and stop the worker with Ctrl+C before registering the event schedules. In a separate terminal, open the root of the [Lemline source checkout](https://github.com/lemline/lemline) matching these docs (the directory containing `examples/tutorial-services`). With Docker Compose running, start the single shop container:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Open [the shop](http://127.0.0.1:8787). The stock starts at 100 units of SKU-1. To repeat this exercise, wait for outstanding workflows to finish, then reset the tutorial shop:

```bash
curl -X POST http://127.0.0.1:8787/reset
```

## Register the sensor and notification workflows

The sensor workflow runs once per reading. Above 80 it emits a high-temperature event on the worker's SQLite event channel.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

A second workflow subscribes to that event and delivers the reading to the warehouse manager's inbox.

<code-block lang="yaml" src="workflows/reference/warehouse-alert.yaml"/>

The publisher injects readings through the same channel.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Save the three definitions under the filenames below. From the setup directory (using `java -jar lemline.jar` in place of `bin/lemline` with the JAR), register them before publishing any reading. Skip definitions already registered at this version.

```bash
bin/lemline definition post -f warehouse-alert.yaml
bin/lemline definition post -f device-telemetry.yaml
bin/lemline definition post -f tutorial-publisher.yaml
```

Start the host worker after registering both schedules so it loads the subscriptions before the first reading arrives. Leave it running and publish from another terminal in the setup directory.

```bash
bin/lemline listen --debug
```

## Exercise both paths

Publish the first reading, wait for the sensor workflow to finish, and refresh the shop. The inbox should be empty. Publish the second reading and refresh again.

The worker picks up events for scheduled workflows every 10 seconds. Allow about 10 seconds for the sensor workflow to finish after a reading, and about 20 seconds for the alert to reach the inbox.

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-1","temperature":70}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-1","temperature":90}}'
```

Filter by `sensor-1`. Expect one entry for `warehouse-manager` with the message `High temperature: 90`. The cooler reading produces no alert. To repeat, reset the shop before sending both readings again.

From the checkout root, stop the shop when finished:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
