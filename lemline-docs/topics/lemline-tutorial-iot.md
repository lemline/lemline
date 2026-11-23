---
title: "Tutorial: Streaming Sensor Events (IoT)"
---

# Tutorial: IoT Telemetry Processing

Use an event schedule to start a new workflow instance each time a device reading arrives.
The example checks the temperature and publishes a second event only above the threshold.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

## Register and start the runner

Save the definition as `device-telemetry.yaml`. Follow
[Getting Started](lemline-getting-started.md) to prepare the runner and its message broker,
then register the definition with `lemline definition post -f device-telemetry.yaml`.

## Exercise both paths

Publish a CloudEvent of type `com.example.device.telemetry.received` through your
configured event channel. Use a payload with a `deviceId` and a numeric `temperature`.
Send readings below and above 80. Only the latter should publish an event of type
`com.example.device.temperature.high`. The outgoing event identifies the device in
its subject and carries the temperature in its data.

See [How to Emit and Listen for Events](lemline-howto-events.md) for event filters
and correlation.
