# IoT Workflow Examples

This document provides practical examples of workflows for Internet of Things (IoT) applications using Lemline. These examples demonstrate how to implement common IoT patterns such as device provisioning, telemetry processing, firmware updates, and event-based monitoring.

## Device Provisioning Workflow

This example demonstrates a workflow for provisioning new IoT devices, including registration, credential management, and initial configuration.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: device-provisioning
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - deviceId
        - deviceType
        - location
      properties:
        deviceId:
          type: string
        deviceType:
          type: string
          enum: [sensor, gateway, controller]
        location:
          type: object
          required:
            - siteId
            - zone
do:
  - validateDevice:
      try:
        - checkDeviceExists:
            call: http
            with:
              method: get
              endpoint: https://iot-registry.example/api/devices/${.deviceId}
              timeout:
                seconds: 5
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
            status: 404
        as: notFoundError
        do:
          - createDeviceRecord:
              call: http
              with:
                method: post
                endpoint: https://iot-registry.example/api/devices
                body:
                  deviceId: ${ .deviceId }
                  deviceType: ${ .deviceType }
                  location: ${ .location }
                  status: "provisioning"
                output: deviceRegistry
              export:
                as: '$context + { deviceRegistry: .deviceRegistry }'

  - generateCredentials:
      try:
        - createCredentials:
            call: http
            with:
              method: post
              endpoint: https://iot-security.example/api/credentials/generate
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
              output: credentials
            export:
              as: '$context + { credentials: .credentials }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry:
          delay:
            seconds: 5
          limit:
            attempt:
              count: 3

  - configureDevice:
      do:
        - generateConfig:
            call: http
            with:
              method: post
              endpoint: https://iot-config.example/api/config/generate
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
                location: ${ .location }
              output: deviceConfig
        - createDigitalTwin:
            call: http
            with:
              method: post
              endpoint: https://digital-twins.example/api/twins
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
                attributes:
                  location: ${ .location }
                  firmware: "1.0.0"
                  status: "provisioning"
              output: digitalTwin

  - activateDevice:
      call: asyncapi
      with:
        document:
          endpoint: https://iot-messaging.example/asyncapi.json
        operation: activateDevice
        server:
          name: production
        message:
          payload:
            deviceId: ${ .deviceId }
            config: ${ .deviceConfig }
            credentials: ${ $context.credentials }

  - waitForDeviceActivation:
      listen:
        to:
          one:
            with:
              type: com.example.iot.device.activated
              correlationId: ${ .deviceId }
        for:
          minutes: 15
      output:
        as: activationResult

  - finalizeProvisioning:
      call: http
      with:
        method: patch
        endpoint: https://iot-registry.example/api/devices/${.deviceId}
        body:
          status: "active"
          lastPing: ${ .activationResult.timestamp }
```

## Telemetry Processing Workflow

This example shows how to implement real-time telemetry processing from IoT devices, including data validation, transformation, and anomaly detection.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: telemetry-processing
  version: 1.0.0
schedule:
  on:
    one:
      with:
        type: com.example.iot.telemetry.batch.received
do:
  - processTelemetryBatch:
      for:
        in: "${ .messages }"
        each: telemetry
      do:
        - validateTelemetry:
            try:
              - validateMessage:
                  call: http
                  with:
                    method: post
                    endpoint: https://iot-validator.example/api/validate
                    body: ${ $telemetry }
                    output: validationResult
            catch:
              - errors:
                  with:
                    type: https://example.com/errors/validation
                as: validationError
                do:
                  - logInvalidTelemetry:
                      call: http
                      with:
                        method: post
                        endpoint: https://logging.example/api/log
                        body:
                          level: "warning"
                          message: "Invalid telemetry received"
                          deviceId: ${ $telemetry.deviceId }
                          error: ${ $validationError }
                  then: exit
        
        - processValidTelemetry:
            do:
              - transformTelemetry:
                  call: http
                  with:
                    method: post
                    endpoint: https://iot-transformer.example/api/transform
                    body: ${ $telemetry }
                    output: transformedData
              - updateTwin:
                  call: http
                  with:
                    method: patch
                    endpoint: https://digital-twins.example/api/twins/${$telemetry.deviceId}/state
                    body: ${ .transformedData.state }
              - detectAnomalies:
                  call: http
                  with:
                    method: post
                    endpoint: https://iot-analytics.example/api/anomaly-detection
                    body: ${ .transformedData }
                    output: anomalyResult
              - processAnomaly:
                  switch:
                    - anomalyDetected:
                        when: ${ .anomalyResult.anomalyDetected == true }
                        then: handleAnomaly
                    - default:
                        then: storeTelemetry

  - handleAnomaly:
      do:
        - createAlert:
            call: http
            with:
              method: post
              endpoint: https://alerts.example/api/alerts
              body:
                deviceId: ${ $telemetry.deviceId }
                severity: ${ .anomalyResult.severity }
                type: ${ .anomalyResult.type }
                value: ${ .anomalyResult.value }
                threshold: ${ .anomalyResult.threshold }
                timestamp: ${ $telemetry.timestamp }
        - notifyOperators:
            switch:
              - criticalAnomaly:
                  when: ${ .anomalyResult.severity == "critical" }
                  then:
                    do:
                      - sendUrgentNotification:
                          call: http
                          with:
                            method: post
                            endpoint: https://notifications.example/api/notify
                            body:
                              channel: "sms"
                              recipients: ${ .anomalyResult.responsibleOperators }
                              message: "CRITICAL ALERT: Device ${$telemetry.deviceId} reported anomalous value ${.anomalyResult.value}"
              - default:
                  then:
                    do:
                      - sendStandardNotification:
                          call: http
                          with:
                            method: post
                            endpoint: https://notifications.example/api/notify
                            body:
                              channel: "email"
                              recipients: ${ .anomalyResult.responsibleOperators }
                              message: "ALERT: Device ${$telemetry.deviceId} reported anomalous value ${.anomalyResult.value}"
      then: storeTelemetry

  - storeTelemetry:
      call: http
      with:
        method: post
        endpoint: https://iot-storage.example/api/telemetry
        body: ${ .transformedData }
```

## Firmware Update Workflow

This example demonstrates a workflow for managing firmware updates across a fleet of IoT devices, including scheduling, deployment, and status tracking.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: firmware-update
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - firmwareVersion
        - deviceQuery
      properties:
        firmwareVersion:
          type: string
        deviceQuery:
          type: object
do:
  - validateFirmware:
      call: http
      with:
        method: get
        endpoint: https://firmware.example/api/firmware/${.firmwareVersion}
        output: firmwareDetails

  - findEligibleDevices:
      call: http
      with:
        method: post
        endpoint: https://iot-registry.example/api/devices/query
        body: ${ .deviceQuery }
        output: eligibleDevices

  - createUpdateCampaign:
      call: http
      with:
        method: post
        endpoint: https://iot-campaigns.example/api/campaigns
        body:
          name: "Firmware update to ${.firmwareVersion}"
          description: "Deploying firmware version ${.firmwareVersion} to ${length(.eligibleDevices.devices)} devices"
          firmwareId: ${ .firmwareDetails.id }
          devices: ${ .eligibleDevices.devices }
        output: campaign
      export:
        as: '$context + { campaignId: .campaign.id }'

  - scheduleBatchedUpdates:
      do:
        - createBatches:
            set:
              batches: ${ chunk(.eligibleDevices.devices, 50) }
        - processBatches:
            for:
              in: "${ .batches }"
              each: batch
              at: batchIndex
            do:
              - deployToDeviceBatch:
                  do:
                    - notifyDevicesBatch:
                        call: asyncapi
                        with:
                          document:
                            endpoint: https://iot-messaging.example/asyncapi.json
                          operation: notifyFirmwareUpdate
                          server:
                            name: production
                          message:
                            payload:
                              devices: ${ $batch }
                              firmwareId: ${ .firmwareDetails.id }
                              firmwareUrl: ${ .firmwareDetails.downloadUrl }
                              campaignId: ${ $context.campaignId }
                    - waitForBatchCompletion:
                        listen:
                          to:
                            all:
                              with:
                                type: com.example.iot.firmware.status
                                filter: '${ .campaignId == $context.campaignId and $batch contains .deviceId }'
                              expect: ${ length($batch) }
                          for:
                            hours: 2
                        output:
                          as: batchResults
                    - updateCampaignProgress:
                        call: http
                        with:
                          method: patch
                          endpoint: https://iot-campaigns.example/api/campaigns/${$context.campaignId}/progress
                          body:
                            batchIndex: ${ $batchIndex }
                            results: ${ .batchResults }

  - finalizeCampaign:
      call: http
      with:
        method: patch
        endpoint: https://iot-campaigns.example/api/campaigns/${$context.campaignId}
        body:
          status: "completed"
          completedAt: ${ now() }
```

## Predictive Maintenance Workflow

This example shows a workflow for implementing predictive maintenance based on IoT device telemetry data.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: predictive-maintenance
  version: 1.0.0
schedule:
  cron: "0 0 * * *"  # Run daily at midnight
do:
  - retrieveDeviceHealthData:
      call: http
      with:
        method: post
        endpoint: https://iot-analytics.example/api/health-metrics
        body:
          timeRange:
            from: ${ dateTimeSubtract(now(), { days: 7 }) }
            to: ${ now() }
        output: deviceHealthData

  - analyzeMaintenance:
      for:
        in: "${ .deviceHealthData.devices }"
        each: device
      do:
        - predictMaintenance:
            call: http
            with:
              method: post
              endpoint: https://iot-ml.example/api/predict-maintenance
              body: ${ $device }
              output: prediction
        - handlePrediction:
            switch:
              - maintenanceNeeded:
                  when: ${ .prediction.probabilityOfFailure > 0.7 }
                  then: scheduleMaintenance
              - maintenanceWarning:
                  when: ${ .prediction.probabilityOfFailure > 0.4 }
                  then: issueMaintanenceWarning
              - default:
                  then: log

  - scheduleMaintenance:
      do:
        - createMaintenanceTicket:
            call: http
            with:
              method: post
              endpoint: https://maintenance.example/api/tickets
              body:
                deviceId: ${ $device.id }
                priority: ${ .prediction.probabilityOfFailure > 0.9 ? "high" : "medium" }
                estimatedFailureDate: ${ .prediction.estimatedFailureDate }
                components: ${ .prediction.affectedComponents }
                recommendation: ${ .prediction.recommendedAction }
              output: ticket
        - notifyMaintenance:
            call: http
            with:
              method: post
              endpoint: https://notifications.example/api/notify
              body:
                channel: "email"
                template: "maintenance_required"
                recipients: ${ $device.responsibleParties }
                data:
                  deviceId: ${ $device.id }
                  deviceName: ${ $device.name }
                  location: ${ $device.location }
                  failureProbability: ${ formatPercent(.prediction.probabilityOfFailure) }
                  ticketId: ${ .ticket.id }

  - issueMaintanenceWarning:
      call: http
      with:
        method: post
        endpoint: https://notifications.example/api/notify
        body:
          channel: "email"
          template: "maintenance_warning"
          recipients: ${ $device.responsibleParties }
          data:
            deviceId: ${ $device.id }
            deviceName: ${ $device.name }
            location: ${ $device.location }
            failureProbability: ${ formatPercent(.prediction.probabilityOfFailure) }
            recommendation: ${ .prediction.recommendedAction }

  - log:
      call: http
      with:
        method: post
        endpoint: https://logging.example/api/log
        body:
          level: "info"
          message: "Device health check completed"
          deviceId: ${ $device.id }
          failureProbability: ${ .prediction.probabilityOfFailure }
```

## Energy Optimization Workflow

This example demonstrates a workflow for optimizing energy usage in smart buildings based on IoT sensor data.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: energy-optimization
  version: 1.0.0
schedule:
  cron: "*/15 * * * *"  # Run every 15 minutes
do:
  - collectEnvironmentalData:
      do:
        - getWeatherForecast:
            call: http
            with:
              method: get
              endpoint: https://weather.example/api/forecast
              query:
                lat: ${ .buildingLocation.latitude }
                lng: ${ .buildingLocation.longitude }
              output: weatherForecast
        - getBuildingOccupancy:
            call: http
            with:
              method: get
              endpoint: https://occupancy.example/api/buildings/${.buildingId}/current
              output: occupancyData
        - getEnergyPricing:
            call: http
            with:
              method: get
              endpoint: https://energy.example/api/pricing/forecast
              query:
                hours: 24
              output: energyPricing

  - retrieveSensorReadings:
      call: http
      with:
        method: get
        endpoint: https://iot-platform.example/api/buildings/${.buildingId}/sensors
        query:
          types: "temperature,humidity,co2,motion"
          minutes: 30
        output: sensorReadings

  - optimizeEnergyUsage:
      call: http
      with:
        method: post
        endpoint: https://energy-optimizer.example/api/optimize
        body:
          buildingId: ${ .buildingId }
          weather: ${ .weatherForecast }
          occupancy: ${ .occupancyData }
          energyPricing: ${ .energyPricing }
          sensorReadings: ${ .sensorReadings }
        output: optimizationPlan

  - applyOptimizations:
      do:
        - updateHVACSettings:
            call: asyncapi
            with:
              document:
                endpoint: https://iot-messaging.example/asyncapi.json
              operation: updateDeviceSettings
              server:
                name: production
              message:
                payload:
                  deviceType: "hvac"
                  buildingId: ${ .buildingId }
                  settings: ${ .optimizationPlan.hvacSettings }
        - updateLightingSettings:
            call: asyncapi
            with:
              document:
                endpoint: https://iot-messaging.example/asyncapi.json
              operation: updateDeviceSettings
              server:
                name: production
              message:
                payload:
                  deviceType: "lighting"
                  buildingId: ${ .buildingId }
                  settings: ${ .optimizationPlan.lightingSettings }

  - monitorEnergyImpact:
      listen:
        to:
          all:
            with:
              type: com.example.iot.settings.applied
              filter: '${ .buildingId == $context.buildingId }'
            expect: 2  # HVAC and lighting confirmations
        for:
          minutes: 5
      output:
        as: confirmations

  - recordOptimizationResults:
      call: http
      with:
        method: post
        endpoint: https://energy-analytics.example/api/optimizations
        body:
          buildingId: ${ .buildingId }
          timestamp: ${ now() }
          optimizationPlan: ${ .optimizationPlan }
          estimatedSavings: ${ .optimizationPlan.estimatedSavings }
          confirmations: ${ .confirmations }
```

## Best Practices for IoT Workflows

When implementing IoT workflows with Lemline, consider these best practices:

1. **Device Identity Management**: Implement secure device identity and credential management
2. **Batch Processing**: Process telemetry data in batches for efficiency
3. **Error Handling**: Use robust error handling and retry mechanisms for device communications
4. **Correlation**: Properly correlate events from the same device across different workflows
5. **Timeouts**: Set appropriate timeouts for device communication, which may be unstable
6. **Stateful Workflows**: Use workflow context for maintaining state about devices
7. **Digital Twins**: Update digital twin representations with the latest device state
8. **Progressive Deployment**: Implement batched or phased deployments for firmware updates
9. **Monitoring**: Include appropriate logging and monitoring for device interactions
10. **Data Validation**: Always validate incoming telemetry data before processing

## Conclusion

These examples demonstrate how to implement common IoT patterns as workflows using Lemline. The workflows cover the entire IoT lifecycle, from device provisioning to telemetry processing, firmware updates, predictive maintenance, and energy optimization.

By leveraging Lemline's workflow capabilities, you can build robust, scalable IoT solutions that handle complex device management and data processing requirements while maintaining reliability and performance.

For more examples of specific integrations, see the [Integration Examples](lemline-examples-integrations.md) document.