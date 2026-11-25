# IoT Workflow Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline.


This document provides practical examples of workflows for Internet of Things (IoT) applications using Lemline. These examples demonstrate how to implement common IoT patterns such as device provisioning, telemetry processing, firmware updates, and event-based monitoring.

## Device Provisioning Workflow

This example demonstrates a workflow for provisioning new IoT devices, including registration, credential management, and initial configuration.

<code-block lang="yaml" src="workflows/lemline-examples-iot/device-provisioning-workflow.yaml"/>

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

## Telemetry Processing Workflow

This workflow starts on each telemetry event received from a device and emits an alert event when the reported temperature is above 80.

<code-block lang="yaml" src="workflows/reference/device-telemetry.yaml"/>

## Firmware Update Workflow

Run the firmware and energy examples with the Lemline runner connected to a message broker.

This workflow requests a firmware update for each eligible device. It waits up to two
hours for a status event matching the campaign and device, then records that result.
The service stores device status before emitting events and exposes it through the
status endpoint, so the workflow can recover results sent before registration.

<code-block lang="yaml" src="workflows/lemline-examples-iot/firmware-update-workflow.yaml"/>

## Predictive Maintenance Workflow

This example shows a workflow for implementing predictive maintenance based on IoT device telemetry data.

<code-block lang="yaml" src="workflows/lemline-examples-iot/predictive-maintenance-workflow.yaml"/>

## Energy Optimization Workflow

This workflow checks sensor readings every 15 minutes, requests HVAC and lighting
changes, and waits up to five minutes for both devices to confirm the same request.
Each device stores its confirmation before emitting an event. The workflow reads
the stored confirmations if its listener times out.

<code-block lang="yaml" src="workflows/lemline-examples-iot/energy-optimization-workflow.yaml"/>

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
