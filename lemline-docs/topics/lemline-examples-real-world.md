# Lemline Real-World Examples

This document presents comprehensive real-world examples that demonstrate how to build complex, practical workflows using the Serverless Workflow DSL with Lemline. These examples combine multiple workflow patterns, integration methods, and error handling strategies to address realistic business scenarios.

## E-Commerce Order Processing

This example demonstrates a complete order processing workflow for an e-commerce system, handling inventory checks, payment processing, shipping, and notifications.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: order-fulfillment
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - orderId
        - customerId
        - items
        - payment
      properties:
        orderId:
          type: string
        customerId:
          type: string
        items:
          type: array
          items:
            type: object
            required:
              - id
              - quantity
            properties:
              id:
                type: string
              quantity:
                type: integer
        payment:
          type: object
          required:
            - method
            - amount
          properties:
            method:
              type: string
              enum: [credit_card, paypal, bank_transfer]
            amount:
              type: number
do:
  - validateOrder:
      try:
        - checkInventory:
            call: http
            with:
              method: post
              endpoint: https://inventory.example.com/api/check
              body: ${ .items }
              output: inventoryStatus
        - validateInventory:
            switch:
              - allInStock:
                  when: ${ .inventoryStatus.allInStock == true }
                  then: processPayment
              - default:
                  then: handleOutOfStock
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry:
          delay:
            seconds: 2
          backoff:
            exponential: {}
          limit:
            attempt:
              count: 3

  - handleOutOfStock:
      do:
        - notifyCustomer:
            call: http
            with:
              method: post
              endpoint: https://notifications.example.com/api/send
              body:
                to: ${ .customerId }
                template: "out_of_stock"
                data:
                  orderId: ${ .orderId }
                  outOfStockItems: ${ .inventoryStatus.outOfStockItems }
        - createBackorder:
            call: http
            with:
              method: post
              endpoint: https://inventory.example.com/api/backorder
              body:
                orderId: ${ .orderId }
                items: ${ .inventoryStatus.outOfStockItems }
      then: exit

  - processPayment:
      try:
        - reserveInventory:
            call: http
            with:
              method: post
              endpoint: https://inventory.example.com/api/reserve
              body:
                orderId: ${ .orderId }
                items: ${ .items }
              output: reservation
        - chargePayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example.com/api/charge
              body:
                orderId: ${ .orderId }
                customerId: ${ .customerId }
                amount: ${ .payment.amount }
                method: ${ .payment.method }
              output: paymentResult
      catch:
        - errors:
            with:
              type: https://example.com/errors/payment/declined
          as: paymentError
          do:
            - releaseInventory:
                call: http
                with:
                  method: post
                  endpoint: https://inventory.example.com/api/release
                  body:
                    reservationId: ${ .reservation.id }
            - notifyCustomer:
                call: http
                with:
                  method: post
                  endpoint: https://notifications.example.com/api/send
                  body:
                    to: ${ .customerId }
                    template: "payment_declined"
                    data:
                      orderId: ${ .orderId }
                      reason: ${ $paymentError.reason }
          then: exit
        - errors:
            with:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
          retry:
            delay:
              seconds: 5
            backoff:
              exponential: {}
            limit:
              attempt:
                count: 3

  - fulfillOrder:
      do:
        - createShippingLabel:
            call: http
            with:
              method: post
              endpoint: https://shipping.example.com/api/label
              body:
                orderId: ${ .orderId }
                customerId: ${ .customerId }
                items: ${ .items }
              output: shippingLabel
        - schedulePickup:
            call: http
            with:
              method: post
              endpoint: https://shipping.example.com/api/pickup
              body:
                labelId: ${ .shippingLabel.id }
              output: pickupDetails
        - updateOrderStatus:
            call: http
            with:
              method: put
              endpoint: https://orders.example.com/api/orders/${.orderId}
              body:
                status: "processing"
                shippingLabel: ${ .shippingLabel }
                pickup: ${ .pickupDetails }
        - notifyCustomer:
            call: http
            with:
              method: post
              endpoint: https://notifications.example.com/api/send
              body:
                to: ${ .customerId }
                template: "order_confirmed"
                data:
                  orderId: ${ .orderId }
                  estimatedDelivery: ${ .pickupDetails.estimatedDelivery }

  - trackFulfillment:
      listen:
        to:
          one:
            with:
              type: com.example.shipping.order.shipped
              correlationId: ${ .orderId }
        for:
          days: 7
      output:
        as: shippingUpdate

  - completeOrder:
      call: http
      with:
        method: put
        endpoint: https://orders.example.com/api/orders/${.orderId}
        body:
          status: "shipped"
          trackingDetails: ${ .shippingUpdate }
```

This comprehensive e-commerce example demonstrates:
- Input schema validation for order data
- Inventory checking with conditional handling for out-of-stock items
- Transactional processing with compensating actions (releasing inventory on payment failure)
- Multiple external service integrations
- Error handling with retries for communication errors
- Specific error handling for payment decline scenarios
- Event listening for shipping updates with correlation
- Status updates at various stages of the workflow
- Customer notifications at key points in the process

## Healthcare Patient Onboarding

This example demonstrates a patient onboarding workflow in a healthcare system, handling registration, insurance verification, appointment scheduling, and document processing.

```yaml
document:
  dsl: 1.0.0
  namespace: healthcare
  name: patient-onboarding
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - patientInfo
        - insuranceInfo
      properties:
        patientInfo:
          type: object
          required:
            - firstName
            - lastName
            - dateOfBirth
            - email
        insuranceInfo:
          type: object
          required:
            - provider
            - policyNumber
do:
  - registerPatient:
      try:
        - createPatientRecord:
            call: http
            with:
              method: post
              endpoint: https://emr.hospital.example/api/patients
              body: ${ .patientInfo }
              output: patientRecord
        - assignMRN:
            set:
              patientMRN: ${ .patientRecord.mrn }
            export:
              as: '$context + { patientMRN: .patientRecord.mrn }'
      catch:
        errors:
          with:
            type: https://example.com/errors/validation
        as: validationError
        do:
          - handleValidationError:
              set:
                status: "rejected"
                errorDetails: ${ $validationError }
              export:
                as: '$context + { status: "rejected", errorDetails: $validationError }'
          then: exit

  - verifyInsurance:
      try:
        - checkInsuranceCoverage:
            call: http
            with:
              method: post
              endpoint: https://insurance.example/api/verify
              body:
                patientMRN: ${ $context.patientMRN }
                provider: ${ .insuranceInfo.provider }
                policyNumber: ${ .insuranceInfo.policyNumber }
              output: insuranceVerification
        - evaluateVerification:
            switch:
              - coverageVerified:
                  when: ${ .insuranceVerification.status == "verified" }
                  then: setupInitialAppointment
              - coveragePending:
                  when: ${ .insuranceVerification.status == "pending" }
                  then: waitForManualVerification
              - default:
                  then: handleRejectedCoverage
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry:
          delay:
            seconds: 60
          limit:
            attempt:
              count: 3

  - waitForManualVerification:
      do:
        - notifyInsuranceTeam:
            call: http
            with:
              method: post
              endpoint: https://notifications.hospital.example/api/internal
              body:
                team: "insurance"
                action: "verify"
                data:
                  patientMRN: ${ $context.patientMRN }
                  insuranceDetails: ${ .insuranceInfo }
        - waitForVerification:
            listen:
              to:
                one:
                  with:
                    type: com.hospital.insurance.verification.completed
                    correlationId: ${ $context.patientMRN }
              for:
                days: 5
            output:
              as: manualVerificationResult
        - checkManualResult:
            switch:
              - coverageVerified:
                  when: ${ .manualVerificationResult.status == "verified" }
                  then: setupInitialAppointment
              - default:
                  then: handleRejectedCoverage

  - handleRejectedCoverage:
      do:
        - updatePatientRecord:
            call: http
            with:
              method: put
              endpoint: https://emr.hospital.example/api/patients/${$context.patientMRN}
              body:
                insuranceStatus: "not_verified"
                paymentMethod: "self_pay"
        - notifyPatient:
            call: http
            with:
              method: post
              endpoint: https://notifications.hospital.example/api/patients/notify
              body:
                patientMRN: ${ $context.patientMRN }
                template: "insurance_not_verified"
                channel: "email"
      then: setupInitialAppointment

  - setupInitialAppointment:
      do:
        - checkAvailableTimes:
            call: http
            with:
              method: get
              endpoint: https://scheduling.hospital.example/api/availability
              query:
                departmentId: "primary_care"
                daysForward: 14
              output: availableSlots
        - sendAppointmentOptions:
            call: http
            with:
              method: post
              endpoint: https://notifications.hospital.example/api/patients/notify
              body:
                patientMRN: ${ $context.patientMRN }
                template: "appointment_options"
                channel: "email"
                data:
                  slots: ${ .availableSlots }
        - waitForSelection:
            listen:
              to:
                one:
                  with:
                    type: com.hospital.appointment.slot.selected
                    correlationId: ${ $context.patientMRN }
              for:
                days: 7
            output:
              as: selectedSlot
        - scheduleAppointment:
            call: http
            with:
              method: post
              endpoint: https://scheduling.hospital.example/api/appointments
              body:
                patientMRN: ${ $context.patientMRN }
                slotId: ${ .selectedSlot.slotId }
              output: appointment

  - processDocuments:
      do:
        - sendDocumentRequests:
            call: http
            with:
              method: post
              endpoint: https://notifications.hospital.example/api/patients/notify
              body:
                patientMRN: ${ $context.patientMRN }
                template: "required_documents"
                channel: "email"
        - processConsentForm:
            listen:
              to:
                one:
                  with:
                    type: com.hospital.document.consent.submitted
                    correlationId: ${ $context.patientMRN }
              for:
                days: 14
            output:
              as: consentForm
        - processMedicalHistory:
            listen:
              to:
                one:
                  with:
                    type: com.hospital.document.history.submitted
                    correlationId: ${ $context.patientMRN }
              for:
                days: 14
            output:
              as: medicalHistory

  - finalizeOnboarding:
      do:
        - updatePatientStatus:
            call: http
            with:
              method: put
              endpoint: https://emr.hospital.example/api/patients/${$context.patientMRN}
              body:
                status: "active"
                onboardingComplete: true
                documents:
                  consent: ${ .consentForm.documentId }
                  medicalHistory: ${ .medicalHistory.documentId }
        - sendWelcomePacket:
            call: http
            with:
              method: post
              endpoint: https://notifications.hospital.example/api/patients/notify
              body:
                patientMRN: ${ $context.patientMRN }
                template: "welcome_packet"
                channel: "email"
                data:
                  appointmentDetails: ${ .appointment }
```

This healthcare example demonstrates:
- Patient registration with validation
- Insurance verification with multiple pathways based on verification status
- Manual intervention handling with event listening
- Appointment scheduling with user interaction
- Document collection through events
- Complex multi-stage process with status tracking
- Context variables to maintain state across the workflow
- Timeout handling for various stages of the process

## Financial Loan Application Processing

This example demonstrates a mortgage loan application workflow, including application submission, credit checking, underwriting, and final approval.

```yaml
document:
  dsl: 1.0.0
  namespace: finance
  name: mortgage-application
  version: 1.0.0
do:
  - receiveApplication:
      listen:
        to:
          one:
            with:
              type: com.bank.mortgage.application.submitted
        output:
          as: application
      export:
        as: '$context + { applicationId: .application.id }'

  - validateApplication:
      try:
        - performInitialValidation:
            call: http
            with:
              method: post
              endpoint: https://validation.bank.example/api/mortgage/validate
              body: ${ .application }
              output: validationResult
        - checkValidationResult:
            switch:
              - validApplication:
                  when: ${ .validationResult.valid == true }
                  then: initiateBackgroundChecks
              - default:
                  then: rejectApplication
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
        as: validationError
        do:
          - logValidationFailure:
              call: http
              with:
                method: post
                endpoint: https://logging.bank.example/api/log
                body:
                  level: "error"
                  source: "mortgage_application"
                  applicationId: ${ $context.applicationId }
                  error: ${ $validationError }
          then: rejectApplication

  - rejectApplication:
      do:
        - updateApplicationStatus:
            call: http
            with:
              method: put
              endpoint: https://applications.bank.example/api/mortgage/${$context.applicationId}
              body:
                status: "rejected"
                reason: ${ .validationResult.reason || "Failed validation" }
        - notifyApplicant:
            call: http
            with:
              method: post
              endpoint: https://notifications.bank.example/api/send
              body:
                to: ${ .application.email }
                template: "mortgage_application_rejected"
                data:
                  applicationId: ${ $context.applicationId }
                  reason: ${ .validationResult.reason || "Failed validation" }
      then: exit

  - initiateBackgroundChecks:
      do:
        - updateApplicationStatus:
            call: http
            with:
              method: put
              endpoint: https://applications.bank.example/api/mortgage/${$context.applicationId}
              body:
                status: "background_checks"
        - performParallelChecks:
            fork:
              - creditCheck:
                  do:
                    - runCreditCheck:
                        call: http
                        with:
                          method: post
                          endpoint: https://credit.bank.example/api/check
                          body:
                            ssn: ${ .application.ssn }
                            name: ${ .application.name }
                          output: creditReport
              - employmentVerification:
                  do:
                    - verifyEmployment:
                        call: http
                        with:
                          method: post
                          endpoint: https://verification.bank.example/api/employment
                          body:
                            employer: ${ .application.employer }
                            applicantName: ${ .application.name }
                            claimed_income: ${ .application.income }
                          output: employmentVerification
              - propertyAppraisal:
                  do:
                    - scheduleAppraisal:
                        call: http
                        with:
                          method: post
                          endpoint: https://appraisal.bank.example/api/schedule
                          body:
                            propertyAddress: ${ .application.propertyAddress }
                            applicantPhone: ${ .application.phone }
                          output: appraisalSchedule
                    - waitForAppraisal:
                        listen:
                          to:
                            one:
                              with:
                                type: com.bank.mortgage.appraisal.completed
                                correlationId: ${ $context.applicationId }
                          for:
                            days: 14
                        output:
                          as: appraisalResult
            output:
              as: backgroundChecks

  - assessApplication:
      do:
        - compileResults:
            set:
              assessmentPackage:
                application: ${ .application }
                creditScore: ${ .backgroundChecks.creditCheck.creditReport.score }
                creditReport: ${ .backgroundChecks.creditCheck.creditReport }
                employmentVerified: ${ .backgroundChecks.employmentVerification.verified }
                income: ${ .backgroundChecks.employmentVerification.verifiedIncome }
                propertyValue: ${ .backgroundChecks.propertyAppraisal.appraisalResult.value }
                loanToValue: ${ .application.loanAmount / .backgroundChecks.propertyAppraisal.appraisalResult.value * 100 }
        - assessRisk:
            call: http
            with:
              method: post
              endpoint: https://risk.bank.example/api/mortgage/assess
              body: ${ .assessmentPackage }
              output: riskAssessment
        - routeApplication:
            switch:
              - lowRisk:
                  when: ${ .riskAssessment.riskLevel == "low" }
                  then: automaticApproval
              - mediumRisk:
                  when: ${ .riskAssessment.riskLevel == "medium" }
                  then: manualUnderwriting
              - default:
                  then: rejectHighRiskApplication

  - automaticApproval:
      do:
        - generateOfferTerms:
            call: http
            with:
              method: post
              endpoint: https://offers.bank.example/api/mortgage/generate
              body:
                applicationId: ${ $context.applicationId }
                creditScore: ${ .assessmentPackage.creditScore }
                loanAmount: ${ .application.loanAmount }
                propertyValue: ${ .assessmentPackage.propertyValue }
                income: ${ .assessmentPackage.income }
              output: offerTerms
        - updateApplicationStatus:
            call: http
            with:
              method: put
              endpoint: https://applications.bank.example/api/mortgage/${$context.applicationId}
              body:
                status: "approved"
                terms: ${ .offerTerms }
        - sendOfferToApplicant:
            call: http
            with:
              method: post
              endpoint: https://notifications.bank.example/api/send
              body:
                to: ${ .application.email }
                template: "mortgage_offer"
                data:
                  applicationId: ${ $context.applicationId }
                  terms: ${ .offerTerms }
        - waitForAcceptance:
            listen:
              to:
                one:
                  with:
                    type: com.bank.mortgage.offer.response
                    correlationId: ${ $context.applicationId }
              for:
                days: 30
            output:
              as: offerResponse
        - processFinalDecision:
            switch:
              - offerAccepted:
                  when: ${ .offerResponse.accepted == true }
                  then: processAcceptedOffer
              - default:
                  then: closeRejectedOffer

  - manualUnderwriting:
      do:
        - assignToUnderwriter:
            call: http
            with:
              method: post
              endpoint: https://workflow.bank.example/api/tasks/assign
              body:
                type: "mortgage_underwriting"
                applicationId: ${ $context.applicationId }
                data: ${ .assessmentPackage }
              output: underwritingTask
        - updateApplicationStatus:
            call: http
            with:
              method: put
              endpoint: https://applications.bank.example/api/mortgage/${$context.applicationId}
              body:
                status: "underwriting"
        - waitForUnderwritingDecision:
            listen:
              to:
                one:
                  with:
                    type: com.bank.mortgage.underwriting.completed
                    correlationId: ${ $context.applicationId }
              for:
                days: 14
            output:
              as: underwritingDecision
        - processUnderwritingResult:
            switch:
              - approved:
                  when: ${ .underwritingDecision.approved == true }
                  then: sendManualApproval
              - default:
                  then: rejectUnderwrittenApplication

  - rejectHighRiskApplication:
      # Similar to rejectApplication but with high risk specific messaging
      then: exit

  - rejectUnderwrittenApplication:
      # Similar to rejectApplication but with underwriting specific messaging
      then: exit

  - sendManualApproval:
      # Similar to automaticApproval but with any special terms from underwriting
      then: waitForAcceptance

  - processAcceptedOffer:
      do:
        - initiateClosingProcess:
            call: http
            with:
              method: post
              endpoint: https://closing.bank.example/api/initiate
              body:
                applicationId: ${ $context.applicationId }
                applicantDetails: ${ .application }
                terms: ${ .offerTerms || .underwritingDecision.terms }

  - closeRejectedOffer:
      do:
        - updateApplicationStatus:
            call: http
            with:
              method: put
              endpoint: https://applications.bank.example/api/mortgage/${$context.applicationId}
              body:
                status: "closed"
                reason: "offer_rejected_by_customer"
        - archiveApplication:
            call: http
            with:
              method: post
              endpoint: https://archive.bank.example/api/store
              body:
                type: "mortgage_application"
                id: ${ $context.applicationId }
                status: "closed"
                data: ${ . }
```

This financial example demonstrates:
- Event-driven workflow initiation
- Complex validation and risk assessment logic
- Parallel background check processes using fork
- Human involvement in the underwriting process
- Decision branching based on risk levels
- Customer interaction through offer acceptance
- Long-running processes with appropriate timeouts
- Comprehensive status tracking and notification

## IoT Device Provisioning and Monitoring

This example demonstrates a workflow for provisioning and monitoring IoT devices, including device registration, configuration, and ongoing telemetry processing.

```yaml
document:
  dsl: 1.0.0
  namespace: iot
  name: device-lifecycle-management
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
            - latitude
            - longitude
            - description
do:
  - registerDevice:
      try:
        - createDeviceIdentity:
            call: http
            with:
              method: post
              endpoint: https://iot-registry.example/api/devices
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
                location: ${ .location }
              output: deviceRegistry
        - generateCredentials:
            call: http
            with:
              method: post
              endpoint: https://iot-security.example/api/credentials/generate
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
              output: credentials
            export:
              as: '$context + { deviceKey: .credentials.key }'
      catch:
        errors:
          with:
            type: https://example.com/errors/device/already-exists
        as: registrationError
        do:
          - retrieveExistingDevice:
              call: http
              with:
                method: get
                endpoint: https://iot-registry.example/api/devices/${.deviceId}
                output: deviceRegistry
          - updateDeviceLocation:
              call: http
              with:
                method: patch
                endpoint: https://iot-registry.example/api/devices/${.deviceId}
                body:
                  location: ${ .location }

  - provisionDevice:
      try:
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
                  firmware: ${ .deviceRegistry.firmwareVersion || "1.0.0" }
                  status: "provisioning"
              output: digitalTwin
        - assignToFleet:
            call: http
            with:
              method: post
              endpoint: https://fleet-manager.example/api/fleet/assign
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
                location: ${ .location }
              output: fleetAssignment
        - prepareFirmwareUpdate:
            switch:
              - needsUpdate:
                  when: ${ .deviceRegistry.firmwareVersion != .deviceRegistry.latestFirmwareVersion }
                  then: scheduleFirmwareUpdate
              - default:
                  then: configureDevice
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry:
          delay:
            seconds: 30
          backoff:
            exponential: {}
          limit:
            attempt:
              count: 5

  - scheduleFirmwareUpdate:
      do:
        - queueFirmwareJob:
            call: http
            with:
              method: post
              endpoint: https://firmware.example/api/updates/schedule
              body:
                deviceId: ${ .deviceId }
                currentVersion: ${ .deviceRegistry.firmwareVersion }
                targetVersion: ${ .deviceRegistry.latestFirmwareVersion }
              output: firmwareJob
      then: configureDevice

  - configureDevice:
      do:
        - generateConfiguration:
            call: http
            with:
              method: post
              endpoint: https://config-manager.example/api/config/generate
              body:
                deviceId: ${ .deviceId }
                deviceType: ${ .deviceType }
                fleetId: ${ .fleetAssignment.fleetId }
              output: deviceConfig
        - pushConfiguration:
            call: asyncapi
            with:
              document:
                endpoint: https://iot-messaging.example/asyncapi.json
              operation: configureDevice
              server:
                name: production
              message:
                payload:
                  deviceId: ${ .deviceId }
                  config: ${ .deviceConfig }
                  credentials: ${ $context.deviceKey }
        - waitForConfigAck:
            listen:
              to:
                one:
                  with:
                    type: com.example.iot.device.configured
                    correlationId: ${ .deviceId }
              for:
                minutes: 30
            output:
              as: configAck

  - monitorDeviceHealth:
      do:
        - updateDeviceStatus:
            call: http
            with:
              method: patch
              endpoint: https://digital-twins.example/api/twins/${.deviceId}
              body:
                status: "active"
        - startTelemetryProcessing:
            call: asyncapi
            with:
              document:
                endpoint: https://iot-messaging.example/asyncapi.json
              operation: subscribeTelemetry
              subscription:
                filter: '${ .deviceId == $context.deviceId }'
                consume:
                  forever:
                    foreach:
                      do:
                        - processTelemetry:
                            try:
                              - analyzeTelemetry:
                                  call: http
                                  with:
                                    method: post
                                    endpoint: https://analytics.example/api/telemetry/analyze
                                    body: ${ . }
                                    output: analysis
                              - updateTwinState:
                                  call: http
                                  with:
                                    method: patch
                                    endpoint: https://digital-twins.example/api/twins/${.deviceId}/state
                                    body: ${ .analysis.state }
                              - checkAnomalies:
                                  switch:
                                    - hasAnomaly:
                                        when: ${ .analysis.anomalyDetected == true }
                                        then:
                                          do:
                                            - triggerAlert:
                                                emit:
                                                  event:
                                                    with:
                                                      source: "device-monitoring"
                                                      type: "com.example.iot.anomaly.detected"
                                                      data:
                                                        deviceId: ${ .deviceId }
                                                        anomaly: ${ .analysis.anomaly }
                                                        severity: ${ .analysis.anomalySeverity }
                                                        timestamp: ${ .timestamp }
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
        - listenForDecommission:
            listen:
              to:
                one:
                  with:
                    type: com.example.iot.device.decommission
                    correlationId: ${ .deviceId }
            output:
              as: decommissionRequest

  - decommissionDevice:
      do:
        - updateDeviceStatus:
            call: http
            with:
              method: patch
              endpoint: https://digital-twins.example/api/twins/${.deviceId}
              body:
                status: "decommissioned"
        - revokeCredentials:
            call: http
            with:
              method: delete
              endpoint: https://iot-security.example/api/credentials/${.deviceId}
        - removeFromFleet:
            call: http
            with:
              method: delete
              endpoint: https://fleet-manager.example/api/fleet/devices/${.deviceId}
        - archiveDeviceData:
            call: http
            with:
              method: post
              endpoint: https://data-archive.example/api/archive
              body:
                deviceId: ${ .deviceId }
                reason: ${ .decommissionRequest.reason }
```

This IoT example demonstrates:
- Device registration and credential management
- Digital twin creation and state management
- Configuration deployment via messaging
- Infinite telemetry processing with forEach loop
- Real-time anomaly detection and alerting
- Device decommissioning process
- Complex event correlation
- Status tracking across the device lifecycle

## Best Practices for Real-World Workflows

Based on these examples, here are some best practices for building robust real-world workflows:

1. **Model the entire business process**: Include all steps from start to finish, capturing the complete lifecycle
2. **Use appropriate error handling**: Implement try-catch blocks with retries for transient failures
3. **Design for long-running processes**: Use events and correlations to handle processes that span days or weeks
4. **Maintain state in context**: Use context variables to maintain state across workflow execution
5. **Implement compensating transactions**: Always clean up after partial failures
6. **Monitor and log key activities**: Include appropriate logging and monitoring steps
7. **Separate business logic from technical details**: Focus workflow definitions on business process, not technical implementation
8. **Include timeouts**: Always set appropriate timeouts for long-running activities and event listeners
9. **Implement idempotent operations**: Design operations to be safely retriable
10. **Include human interaction points**: Use events to handle human approvals and decisions

## Conclusion

These real-world examples demonstrate how to implement complex business processes as workflows using the Serverless Workflow DSL in Lemline. By combining basic patterns, integrations, and error handling strategies, you can build robust, maintainable workflows for virtually any business scenario.

The examples shown here represent common patterns across various industries, but the same principles can be applied to workflows in any domain, from manufacturing to healthcare, finance to e-commerce, and beyond.

For more specific patterns, refer to the other example documents in this library:
- [Basic Patterns Examples](lemline-examples-basic-patterns.md)
- [Integration Examples](lemline-examples-integrations.md) 
- [Error Handling Examples](lemline-examples-error-handling.md)