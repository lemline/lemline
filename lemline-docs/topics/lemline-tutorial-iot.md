---
title: "Tutorial: Streaming Sensor Events (IoT)"
---

# Tutorial: Streaming Sensor Events (IoT)

This tutorial demonstrates how to use Lemline for IoT event streaming scenarios. You'll build a workflow that processes
sensor data streams, applies filtering logic, performs real-time analytics, and triggers alerts based on aggregated
readings.

## Learning Objectives

By completing this tutorial, you will learn:

- How to handle high-volume event streams efficiently
- How to implement filtering and aggregation logic
- How to process IoT data with windowing patterns
- How to trigger actions based on sensor data patterns
- How Lemline's architecture excels for high-scale IoT scenarios

## Prerequisites

- Completion of the [Waits, Fan-In, and Timers](lemline-tutorial-waits.md) tutorial
- Basic understanding of event streaming concepts
- Lemline runtime installed and configured
- Kafka or RabbitMQ message broker available (for high-volume testing)

## 1. Understanding IoT Event Processing Challenges

IoT applications present specific challenges:

- High-volume event streams (potentially thousands of events per second)
- Need for real-time processing with low latency
- Stateful operations like windowing and aggregation
- Complex event correlation across multiple devices
- Reliable error handling with exactly-once semantics

Traditional workflow engines struggle with these scenarios due to database bottlenecks. Lemline's event-driven
architecture makes it particularly suitable for IoT use cases.

## 2. Setting Up Your Project

Work in the `lemline-project` directory from [Setting Up a Local Environment](lemline-tutorial-setup.md). Keep the worker running with the `examples` namespace enabled.

## 3. Creating the Sensor Data Processing Workflow

Let's create a workflow that processes temperature sensor data from multiple rooms, identifies patterns, and triggers
alerts. Create a file named `temperature-monitoring.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: temperature-monitoring
  version: 1.0.0
  title: Temperature Monitoring System
do:
  - configure:
      set:
        temperature: { low: 16, high: 28 }
        humidity: { low: 30, high: 70 }
      export: { as: '${ $context + {thresholds: .} }' }
```

## 4. Implementing Continuous Monitoring

Let's add the continuous monitoring logic by processing sensor events:

```yaml
  - MonitorTemperatures:
      listen:
        to:
          one:
            with: { type: SensorReading }
            correlate:
              buildingId:
                from: ${ .data.buildingId }
                expect: ${ $workflow.input.buildingId }
        read: data
  - routeReading:
      switch:
        - temperature: { when: '${ .type == "temperature" }', then: ProcessTemperatureReading }
        - humidity: { when: '${ .type == "humidity" }', then: ProcessHumidityReading }
        - ignore: { then: MonitorTemperatures }
```

Each pass waits for one matching sensor reading. The switch routes its data to the temperature or humidity handler. Each handler returns to `MonitorTemperatures` to receive the next event.

## 5. Processing Temperature Readings

Now let's add the temperature processing logic:

```yaml
  - ProcessTemperatureReading:
      if: '${ .value < $context.thresholds.temperature.low or .value > $context.thresholds.temperature.high }'
      emit:
        event:
          with:
            source: https://example.com/sensors
            type: TemperatureAnomaly
            data: ${ . }
  - resumeAfterTemperature:
      set: ${ . }
      then: MonitorTemperatures
```

## 6. Processing Humidity Readings

Similarly, let's add humidity processing:

```yaml
  - ProcessHumidityReading:
      if: '${ .value < $context.thresholds.humidity.low or .value > $context.thresholds.humidity.high }'
      emit:
        event:
          with:
            source: https://example.com/sensors
            type: HumidityAnomaly
            data: ${ . }
  - resumeAfterHumidity:
      set: ${ . }
      then: MonitorTemperatures
```

## 7. Adding Anomaly Pattern Detection

Now let's add a parallel workflow that aggregates anomalies and detects patterns. Create a file named
`anomaly-detector.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: anomaly-detector
  version: 1.0.0
  title: Anomaly Pattern Detector
do:
  - receive:
      listen:
        to:
          one:
            with: { type: TemperatureAnomaly }
            correlate:
              buildingId:
                from: ${ .data.buildingId }
                expect: ${ $workflow.input.buildingId }
        read: data
  - updateWindow:
      set: >-
        ${ . as $reading | now as $received |
        [($context.recent // [])[] | select(.received >= ($received - 300))] +
        [{roomId: $reading.roomId, received: $received}] }
      export: { as: '${ $context + {recent: .} }' }
  - identifyRooms:
      set:
        rooms: ${ group_by(.roomId) | map(select(length >= 3) | .[0].roomId) }
  - raiseAlert:
      if: ${ .rooms | length > 0 }
      emit:
        event:
          with:
            source: https://example.com/sensors
            type: TemperaturePatternAlert
            data:
              buildingId: ${ $workflow.input.buildingId }
              rooms: ${ .rooms }
  - resume:
      set: ${ . }
      then: receive
```

This pattern detection workflow:

1. Listens for temperature anomalies within a 5-minute window
2. Groups anomalies by room to detect patterns
3. Triggers alerts when multiple anomalies are detected in the same room
4. Implements a sliding window pattern for continuous monitoring

## 8. Testing the Workflows

You can test these workflows by:

1. Start both workflows in separate terminals:

```bash
bin/lemline definition post -f temperature-monitoring.yaml
bin/lemline instance start examples temperature-monitoring --input '{"buildingId": "BLDG-123"}'
bin/lemline definition post -f anomaly-detector.yaml
bin/lemline instance start examples anomaly-detector --input '{"buildingId": "BLDG-123"}'
```

2. Simulate sensor readings by publishing events:

Save the following helper as `publish-event.yaml`. Each instance emits one CloudEvent in the tutorial namespace.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: publish-event
  version: '1.0.0'
do:
  - publish:
      emit:
        event:
          with:
            source: https://example.com/tutorial
            type: ${ .type }
            data: ${ .data }
```

```bash
bin/lemline definition post -f publish-event.yaml
```

```bash
# Normal temperature reading
bin/lemline instance start examples publish-event --input '{"type": "SensorReading", "data": {"buildingId": "BLDG-123", "roomId": "ROOM-101", "type": "temperature", "value": 22.5, "timestamp": "2023-09-10T14:30:00Z"}}'

# High temperature reading
bin/lemline instance start examples publish-event --input '{"type": "SensorReading", "data": {"buildingId": "BLDG-123", "roomId": "ROOM-101", "type": "temperature", "value": 30.2, "timestamp": "2023-09-10T14:31:00Z"}}'

# Another high temperature reading for the same room
bin/lemline instance start examples publish-event --input '{"type": "SensorReading", "data": {"buildingId": "BLDG-123", "roomId": "ROOM-101", "type": "temperature", "value": 31.5, "timestamp": "2023-09-10T14:32:00Z"}}'

# A third high temperature reading for the same room - should trigger alert
bin/lemline instance start examples publish-event --input '{"type": "SensorReading", "data": {"buildingId": "BLDG-123", "roomId": "ROOM-101", "type": "temperature", "value": 32.1, "timestamp": "2023-09-10T14:33:00Z"}}'
```

## 9. Scaling for Production

For production IoT scenarios, consider these deployment strategies:

1. **Horizontal Scaling**: Run multiple Lemline runner instances to distribute the load
2. **Message Partitioning**: Partition events by buildingId or other logical groupings
3. **Stream Processing**: Use Kafka Streams or other stream processing for pre-filtering
4. **High-Availability Setup**: Deploy runners across multiple availability zones
5. **Monitoring**: Set up comprehensive monitoring of your Lemline instances

## 10. The Lemline Advantage for IoT

Lemline's architecture provides several advantages for IoT scenarios:

1. **Minimal Database Overhead**: Most sensor data processing happens without database writes
2. **Event-Driven Design**: Naturally fits the streaming nature of IoT data
3. **Horizontal Scalability**: Easily scales to handle millions of events
4. **Stateful When Needed**: Can maintain state for windowing and aggregation when required
5. **Reliable Error Handling**: Ensures no events are lost even during failures
6. **Declarative Logic**: Simplifies complex event correlation patterns

## What You've Learned

In this tutorial, you've learned how to:

- Process continuous streams of IoT sensor data
- Implement filtering and routing logic for different sensor types
- Detect anomalies in real-time sensor readings
- Implement windowing logic to detect patterns over time
- Trigger alerts based on identified patterns
- Design workflows that scale for high-volume IoT scenarios

## Next Steps

To further explore Lemline's capabilities for IoT:

- Learn about [Message Correlation Strategies](lemline-explain-fan-in.md) for more complex patterns
- Explore [How to configure brokers](lemline-howto-brokers.md) for optimal performance
- Study [Database I/O Analysis](lemline-observability-io.md) to understand performance characteristics
- Check out the [IoT Event Processing Example](lemline-examples-iot.md) for a production-ready implementation
