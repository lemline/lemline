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

Create a new directory for this tutorial:

```bash
mkdir iot-monitoring
cd iot-monitoring
```

## 3. Creating the Sensor Data Processing Workflow

Let's create a workflow that processes temperature sensor data from multiple rooms, identifies patterns, and triggers
alerts. Create a file named `temperature-monitoring.yaml`:

```yaml
id: temperature-monitoring
name: Temperature Monitoring System
version: '1.0'
specVersion: '1.0'
start: InitializeMonitoring
functions:
    -   name: logFunction
        type: custom
        operation: log
    -   name: alertFunction
        type: http
        operation: POST
        url: https://alerts-api.example.com/api/v1/alerts
tasks:
    -   name: InitializeMonitoring
        type: set
        data:
            buildingId: "BLDG-123"
            temperatureThresholdHigh: 28
            temperatureThresholdLow: 16
            humidityThresholdHigh: 70
            humidityThresholdLow: 30
            message: "Initializing temperature monitoring for building BLDG-123"
        next: LogInitialization

    -   name: LogInitialization
        type: call
        function: logFunction
        data:
            log: ".message"
        next: MonitorTemperatures
```

## 4. Implementing Continuous Monitoring

Let's add the continuous monitoring logic by processing sensor events:

```yaml
  -   name: MonitorTemperatures
      type: listen
      events:
          -   name: TemperatureEvents
              type: SensorReading
              correlations:
                  - buildingId = .buildingId
              consume: forever
              foreach:
                  as: "sensorReading"
                  next: ProcessSensorReading
      next: EndMonitoring

  -   name: ProcessSensorReading
      type: switch
      conditions:
          -   condition: ".sensorReading.type == 'temperature'"
              next: ProcessTemperatureReading
          -   condition: ".sensorReading.type == 'humidity'"
              next: ProcessHumidityReading
          -   condition: true
              next: IgnoreReading

  -   name: IgnoreReading
      type: set
      data:
          message: "Ignoring unknown sensor reading type: .sensorReading.type"
      next: LogIgnoredReading

  -   name: LogIgnoredReading
      type: call
      function: logFunction
      data:
          log: ".message"
      end: false
```

This pattern uses the `consume: forever` with `foreach` to continuously process each incoming sensor reading. The key
points are:

- Using `end: false` to return to the listener task
- Using `consume: forever` to continuously listen for events
- Using `foreach` to process each event individually
- Using a switch task to route different types of readings

## 5. Processing Temperature Readings

Now let's add the temperature processing logic:

```yaml
  -   name: ProcessTemperatureReading
      type: set
      data:
          roomId: ".sensorReading.roomId"
          temperature: ".sensorReading.value"
          timestamp: ".sensorReading.timestamp"
          isTooHigh: ".temperature > .temperatureThresholdHigh"
          isTooLow: ".temperature < .temperatureThresholdLow"
          isOutOfRange: ".isTooHigh || .isTooLow"
      next: CheckTemperatureRange

  -   name: CheckTemperatureRange
      type: switch
      conditions:
          -   condition: ".isOutOfRange == true"
              next: RecordTemperatureAnomaly
          -   condition: true
              next: LogNormalTemperature

  -   name: LogNormalTemperature
      type: call
      function: logFunction
      data:
          log: "Normal temperature reading: .temperature°C in room .roomId"
      end: false

  -   name: RecordTemperatureAnomaly
      type: emit
      data:
          eventType: "TemperatureAnomaly"
          payload:
              roomId: ".roomId"
              temperature: ".temperature"
              timestamp: ".timestamp"
              threshold: ".isTooHigh ? .temperatureThresholdHigh : .temperatureThresholdLow"
              type: ".isTooHigh ? 'TOO_HIGH' : 'TOO_LOW'"
      next: LogTemperatureAnomaly

  -   name: LogTemperatureAnomaly
      type: call
      function: logFunction
      data:
          log: "Anomaly detected: Temperature .temperature°C in room .roomId is .isTooHigh ? 'too high' : 'too low'"
      end: false
```

## 6. Processing Humidity Readings

Similarly, let's add humidity processing:

```yaml
  -   name: ProcessHumidityReading
      type: set
      data:
          roomId: ".sensorReading.roomId"
          humidity: ".sensorReading.value"
          timestamp: ".sensorReading.timestamp"
          isTooHigh: ".humidity > .humidityThresholdHigh"
          isTooLow: ".humidity < .humidityThresholdLow"
          isOutOfRange: ".isTooHigh || .isTooLow"
      next: CheckHumidityRange

  -   name: CheckHumidityRange
      type: switch
      conditions:
          -   condition: ".isOutOfRange == true"
              next: RecordHumidityAnomaly
          -   condition: true
              next: LogNormalHumidity

  -   name: LogNormalHumidity
      type: call
      function: logFunction
      data:
          log: "Normal humidity reading: .humidity% in room .roomId"
      end: false

  -   name: RecordHumidityAnomaly
      type: emit
      data:
          eventType: "HumidityAnomaly"
          payload:
              roomId: ".roomId"
              humidity: ".humidity"
              timestamp: ".timestamp"
              threshold: ".isTooHigh ? .humidityThresholdHigh : .humidityThresholdLow"
              type: ".isTooHigh ? 'TOO_HIGH' : 'TOO_LOW'"
      next: LogHumidityAnomaly

  -   name: LogHumidityAnomaly
      type: call
      function: logFunction
      data:
          log: "Anomaly detected: Humidity .humidity% in room .roomId is .isTooHigh ? 'too high' : 'too low'"
      end: false

  -   name: EndMonitoring
      type: set
      data:
          message: "Monitoring ended for building .buildingId"
      next: LogMonitoringEnd

  -   name: LogMonitoringEnd
      type: call
      function: logFunction
      data:
          log: ".message"
      end: true
```

## 7. Adding Anomaly Pattern Detection

Now let's add a parallel workflow that aggregates anomalies and detects patterns. Create a file named
`anomaly-detector.yaml`:

```yaml
id: anomaly-detector
name: Anomaly Pattern Detector
version: '1.0'
specVersion: '1.0'
start: InitializeDetector
functions:
    -   name: logFunction
        type: custom
        operation: log
    -   name: alertFunction
        type: http
        operation: POST
        url: https://alerts-api.example.com/api/v1/alerts
tasks:
    -   name: InitializeDetector
        type: set
        data:
            buildingId: "BLDG-123"
            anomalyThreshold: 3
            timeWindowMinutes: 5
            message: "Initializing anomaly pattern detector for building BLDG-123"
        next: LogInitialization

    -   name: LogInitialization
        type: call
        function: logFunction
        data:
            log: ".message"
        next: WatchForTemperatureAnomalies

    -   name: WatchForTemperatureAnomalies
        type: listen
        events:
            -   name: TemperatureAnomalies
                type: TemperatureAnomaly
                correlations:
                    - buildingId = .buildingId
                timeouts:
                    eventTimeout: PT5M
                    eventTimeoutNext: CheckAnomalyPatterns
                consume:
                    while: "elapsed < toMillis(minutes(.timeWindowMinutes))"
        next: CheckAnomalyPatterns

    -   name: CheckAnomalyPatterns
        type: set
        data:
            anomalies: ".events.TemperatureAnomalies || []"
            anomalyCount: ".anomalies | length"
            roomCounts: "group_by(.roomId) | map({roomId: .[0].roomId, count: length})"
            roomsWithMultipleAnomalies: "[.roomCounts[] | select(.count >= .anomalyThreshold)] | map(.roomId)"
            hasPattern: ".roomsWithMultipleAnomalies | length > 0"
            message: ".anomalyCount temperature anomalies detected in the last .timeWindowMinutes minutes"
        next: LogAnomalyCheck

    -   name: LogAnomalyCheck
        type: call
        function: logFunction
        data:
            log: ".message"
        next: CheckForPatterns

    -   name: CheckForPatterns
        type: switch
        conditions:
            -   condition: ".hasPattern == true"
                next: TriggerTemperatureAlert
            -   condition: true
                next: WatchForTemperatureAnomalies

    -   name: TriggerTemperatureAlert
        type: call
        function: alertFunction
        data:
            alertType: "TEMPERATURE_PATTERN"
            buildingId: ".buildingId"
            rooms: ".roomsWithMultipleAnomalies"
            anomalyCount: ".anomalyCount"
            message: "Multiple temperature anomalies detected in rooms: .roomsWithMultipleAnomalies | join(', ')"
            timestamp: "$WORKFLOW.currentTime"
            severity: "HIGH"
        next: LogAlertSent

    -   name: LogAlertSent
        type: call
        function: logFunction
        data:
            log: "Alert sent for temperature anomaly pattern in rooms: .roomsWithMultipleAnomalies | join(', ')"
        next: WaitBeforeNextWindow

    -   name: WaitBeforeNextWindow
        type: wait
        data:
            duration: PT1M
        next: WatchForTemperatureAnomalies
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
java -jar lemline-runner.jar workflow run temperature-monitoring.yaml --data '{"buildingId": "BLDG-123"}'
java -jar lemline-runner.jar workflow run anomaly-detector.yaml --data '{"buildingId": "BLDG-123"}'
```

2. Simulate sensor readings by publishing events:

```bash
# Normal temperature reading
java -jar lemline-runner.jar events publish SensorReading '{
  "buildingId": "BLDG-123",
  "roomId": "ROOM-101",
  "type": "temperature",
  "value": 22.5,
  "timestamp": "2023-09-10T14:30:00Z"
}'

# High temperature reading
java -jar lemline-runner.jar events publish SensorReading '{
  "buildingId": "BLDG-123",
  "roomId": "ROOM-101",
  "type": "temperature",
  "value": 30.2,
  "timestamp": "2023-09-10T14:31:00Z"
}'

# Another high temperature reading for the same room
java -jar lemline-runner.jar events publish SensorReading '{
  "buildingId": "BLDG-123",
  "roomId": "ROOM-101",
  "type": "temperature",
  "value": 31.5,
  "timestamp": "2023-09-10T14:32:00Z"
}'

# A third high temperature reading for the same room - should trigger alert
java -jar lemline-runner.jar events publish SensorReading '{
  "buildingId": "BLDG-123",
  "roomId": "ROOM-101",
  "type": "temperature",
  "value": 32.1,
  "timestamp": "2023-09-10T14:33:00Z"
}'
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
