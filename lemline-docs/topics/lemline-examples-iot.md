# IoT Workflow Examples

This document provides practical examples of workflows for Internet of Things (IoT) applications using Lemline. They
manage the sensors of the tutorial shop's warehouse: device provisioning, telemetry processing, firmware updates,
predictive maintenance and energy optimization.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and run the commands from the setup directory. With the JAR,
substitute `java -jar lemline.jar` for `bin/lemline`. Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop)
from the root of the Lemline source checkout. Its `/devices` routes hold three warehouse sensors; the shop page lists
them under **Devices**.

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Devices answer through events, which the tutorial publisher sends for you. The telemetry workflow is started by events,
so it must be registered before the worker starts. Stop the setup worker with Ctrl+C, save the publisher below as
`tutorial-publisher.yaml` and the [telemetry workflow](#telemetry-processing-workflow) as `device-telemetry.yaml`,
register both and start the worker again:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f device-telemetry.yaml
bin/lemline listen --debug
```

Run the other commands from a second terminal in the setup directory. Publish each device event only after the
instance that waits for it has started waiting: an event only resumes the instances already listening.

## Device Provisioning Workflow

This example provisions a new warehouse sensor: it registers it if the shop does not know it, obtains credentials, stores its configuration, asks the device to activate and waits for its confirmation.

<code-block lang="yaml" src="workflows/lemline-examples-iot/device-provisioning-workflow.yaml"/>

Save it as `device-provisioning-workflow.yaml`:

```bash
bin/lemline definition post -f device-provisioning-workflow.yaml
bin/lemline instance start examples device-provisioning --input '{"deviceId":"sensor-4","deviceType":"sensor","location":{"siteId":"warehouse-1","zone":"office"}}'
```

The shop answers HTTP 404 for the unknown `sensor-4`, so the catch registers it. The instance then waits for the
activation. Send it as the device would:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.iot.device.activated","data":{"deviceId":"sensor-4","timestamp":"2026-11-25T10:00:00Z"}}'
```

The instance completes, and the shop shows `sensor-4` with status `active`, its configuration and the `lastPing` of the event.

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

## Telemetry Processing Workflow

This workflow starts on each telemetry event received from a device and emits an alert event when the reported temperature is above 80.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

You registered it before starting the worker. Publish a normal and a high reading:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-2","temperature":19}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.device.telemetry.received","data":{"deviceId":"sensor-2","temperature":85}}'
```

Each reading starts a `device-telemetry` instance about 10 seconds later; only the second emits
`com.example.device.temperature.high`.

## Firmware Update Workflow

This workflow requests a firmware update for each eligible device of a zone, those whose firmware is older than
the target version. For each one it waits for a status event matching the campaign and the device, then records the
installed version. If no status arrives within 60 seconds, it reads the device's current state from the shop instead.
A real campaign would wait hours.

<code-block lang="yaml" src="workflows/lemline-examples-iot/firmware-update-workflow.yaml"/>

Save it as `firmware-update-workflow.yaml`:

```bash
bin/lemline definition post -f firmware-update-workflow.yaml
bin/lemline instance start examples firmware-update --input '{"firmwareVersion":"1.1.0","campaignId":"CMP-1","zone":"dock"}'
```

In the dock, only `sensor-2` runs a firmware older than 1.1.0. Once the instance waits for it, report the installation:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.iot.firmware.status","data":{"campaignId":"CMP-1","deviceId":"sensor-2","status":"installed","version":"1.1.0"}}'
```

The shop now shows `sensor-2` with firmware `1.1.0`. To see the fallback, start a campaign for the cold room and send
no status:

```bash
bin/lemline instance start examples firmware-update --input '{"firmwareVersion":"1.1.0","campaignId":"CMP-2","zone":"cold-room"}'
```

After 60 to 70 seconds the listener times out, the workflow reads `sensor-1` from the shop, and its firmware stays `1.0.0`.

## Predictive Maintenance Workflow

This workflow runs every 24 hours. For each device it asks the shop for a failure prediction, then opens a maintenance ticket above 0.7, warns the maintenance team above 0.4, and does nothing otherwise. `schedule.every` runs it once when it is started, then at each interval; `schedule.cron` would wait for the next matching time instead.

<code-block lang="yaml" src="workflows/lemline-examples-iot/predictive-maintenance-workflow.yaml"/>

Save it as `predictive-maintenance-workflow.yaml`:

```bash
bin/lemline definition post -f predictive-maintenance-workflow.yaml
bin/lemline instance start examples predictive-maintenance
```

The predictions are 0.13 for `sensor-1`, 0.78 for `sensor-2` and 0.42 for `sensor-3`: the run opens a
`Maintenance ticket` document for `sensor-2` and leaves a `Failure probability 0.42` message for `maintenance` about
`sensor-3`. The schedule repeats every 24 hours while a worker runs.

## Energy Optimization Workflow

This workflow runs every 15 minutes. It asks the shop for a plan based on the zone's sensor readings, requests the
HVAC and lighting changes with two events, and waits up to five minutes for both devices to confirm the same request.
Without both confirmations it records the optimization with none.

<code-block lang="yaml" src="workflows/lemline-examples-iot/energy-optimization-workflow.yaml"/>

Save it as `energy-optimization-workflow.yaml`:

```bash
bin/lemline definition post -f energy-optimization-workflow.yaml
bin/lemline instance start examples energy-optimization --input '{"zone":"dock","requestId":"REQ-1"}'
```

The dock's two sensors average 20 °C, so the plan sets the HVAC to 19 and the lighting to 70. Confirm both changes:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.iot.settings.applied","data":{"requestId":"REQ-1","deviceType":"hvac"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.iot.settings.applied","data":{"requestId":"REQ-1","deviceType":"lighting"}}'
```

The instance completes and the shop's audit list shows `Optimization applied: hvac 19, lighting 70, 2 confirmations`
for the dock. The schedule repeats every 15 minutes while a worker runs; stop the worker when you are done. Stop the
shop too:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Best Practices for IoT Workflows

When implementing IoT workflows with Lemline, consider these best practices:

1. **Device Identity Management**: Implement secure device identity and credential management
2. **Batch Processing**: Process telemetry data in batches for efficiency
3. **Error Handling**: Use robust error handling and retry mechanisms for device communications
4. **Correlation**: Properly correlate events from the same device across different workflows
5. **Timeouts**: Set appropriate timeouts for device communication, which may be unstable (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
6. **Stateful Workflows**: Use workflow context for maintaining state about devices
7. **Digital Twins**: Update digital twin representations with the latest device state
8. **Progressive Deployment**: Implement batched or phased deployments for firmware updates
9. **Monitoring**: Include appropriate logging and monitoring for device interactions
10. **Data Validation**: Always validate incoming telemetry data before processing

## Conclusion

These examples demonstrate how to implement common IoT patterns as workflows using Lemline. The workflows cover the entire IoT lifecycle, from device provisioning to telemetry processing, firmware updates, predictive maintenance, and energy optimization.

By leveraging Lemline's workflow capabilities, you can build robust, scalable IoT solutions that handle complex device management and data processing requirements while maintaining reliability and performance.

For more examples of specific integrations, see the [Integration Examples](lemline-examples-integrations.md) document.

Run the [warehouse exercise](lemline-tutorial-iot.md) to deliver a real event to the manager’s inbox.
