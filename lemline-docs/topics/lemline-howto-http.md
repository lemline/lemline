---
title: How to make an HTTP call
---

# How to make an HTTP call

> **Runtime support:** the Lemline runtime supports only `basic` and `bearer` authentication on HTTP calls (OAuth 2.0 fails with a `configuration` error), and does not expand URI template placeholders such as `{userId}`: build dynamic URIs with a runtime expression instead. See [HTTP Reference](lemline-ref-http.md).


This guide explains how to integrate HTTP/HTTPS API calls into your Lemline workflows. You'll learn how to define HTTP functions, make different types of requests, handle responses, and implement error handling.

## Understanding HTTP Calls in Lemline

HTTP/HTTPS calls are one of the most common ways workflows interact with external systems. Lemline provides robust support for HTTP integration through the `call` task type with HTTP functions.

## Basic HTTP Call Structure

To make an HTTP call in Lemline, you need to:

1. Define an HTTP function in the `functions` section
2. Create a `call` task that uses the function
3. Handle the response in subsequent tasks

Here's a basic example:

```yaml
use:
  functions:
    getWeather:
      call: http
      with:
        method: get
        endpoint: https://api.weather.com/forecast?location={location}
        query: ${ . }
do:
  - FetchWeather:
      call: getWeather
      with:
        location: "New York"
      then: ProcessWeatherData
  - ProcessWeatherData:
      set:
        temperature: ${ .temperature }
        conditions: ${ .conditions }
        message: ${ "The temperature in New York is \(.temperature)\u00b0C with \(.conditions)" }
      then: NextTask
```

## HTTP Function Definition

The HTTP function definition includes the following elements:

```yaml
use:
  functions:
    userService:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/users/{userId}
        query: ${ . }
```

### Supported HTTP Methods

Lemline supports all standard HTTP methods:

- `GET`: Retrieve data
- `POST`: Create a resource or submit data
- `PUT`: Update a resource (full replacement)
- `PATCH`: Partially update a resource
- `DELETE`: Remove a resource
- `HEAD`: Retrieve headers only
- `OPTIONS`: Get supported methods/options

### URL Parameters

You can include path parameters in the URL using curly braces:

```yaml
url: https://api.example.com/users/{userId}/orders/{orderId}
```

These parameters are replaced with actual values from the task's `data` section when the call is made.

## Making Different Types of HTTP Calls

### Simple GET Request

```yaml
use:
  functions:
    getUserProfile:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/users/{userId}
        query: ${ . }
do:
  - FetchUserProfile:
      call: getUserProfile
      with:
        userId: ${ .userId }
      then: ProcessProfile
```

### POST Request with JSON Body

```yaml
use:
  functions:
    createOrder:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/orders
        body: ${ . }
do:
  - SubmitOrder:
      call: createOrder
      with:
        body:
          customerId: ${ .customerId }
          items: ${ .items }
          shippingAddress: ${ .shippingAddress }
          paymentMethod: ${ .paymentMethod }
      then: ProcessOrderResponse
```

The `body` field automatically gets serialized as JSON and sent as the request body.

### PUT Request to Update a Resource

```yaml
use:
  functions:
    updateUser:
      call: http
      with:
        method: put
        endpoint: https://api.example.com/users/{userId}
        body: ${ . }
do:
  - UpdateUserProfile:
      call: updateUser
      with:
        userId: ${ .userId }
        body:
          name: ${ .updatedName }
          email: ${ .updatedEmail }
          preferences: ${ .updatedPreferences }
      then: HandleUpdateResponse
```

### DELETE Request

```yaml
use:
  functions:
    cancelOrder:
      call: http
      with:
        method: delete
        endpoint: https://api.example.com/orders/{orderId}
        body: ${ . }
do:
  - CancelExistingOrder:
      call: cancelOrder
      with:
        orderId: ${ .orderId }
        query:
          reason: ${ .cancellationReason }
      then: HandleCancellationResponse
```

## Request Configuration Options

### Query Parameters

You can add query parameters using the `query` field:

```yaml
  - SearchProducts:
      call: productSearch
      with:
        query:
          category: ${ .productCategory }
          minPrice: ${ .priceRange.min }
          maxPrice: ${ .priceRange.max }
          sortBy: "price"
          order: "asc"
      then: ProcessSearchResults
```

These will be appended to the URL as `?category=...&minPrice=...&maxPrice=...&sortBy=price&order=asc`.

### Headers

You can set custom headers using the `headers` field:

```yaml
  - MakeApiCall:
      call: apiService
      with:
        headers:
          Content-Type: "application/json"
          Accept: "application/json"
          X-API-Key: ${ .apiKey }
          X-Request-ID: ${ $context.id }
      then: ProcessResponse
```

### Request Body Formats

By default, JSON bodies are sent with `Content-Type: application/json`. You can specify other formats:

#### Form Data

```yaml
  - SubmitForm:
      call: formProcessor
      with:
        headers:
          Content-Type: "application/x-www-form-urlencoded"
        body:
          name: ${ .user.name }
          email: ${ .user.email }
          subscribe: "true"
      then: ProcessFormResponse
```

#### Multipart Form Data

```yaml
  - UploadFile:
      call: fileUpload
      with:
        headers:
          Content-Type: "multipart/form-data"
        body:
          file: ${ .fileContent }
          filename: ${ .fileName }
          description: ${ .fileDescription }
      then: ProcessUploadResponse
```

#### Raw Text

```yaml
  - SendPlainText:
      call: textProcessor
      with:
        headers:
          Content-Type: "text/plain"
        body: ${ .textContent }
      then: ProcessTextResponse
```

## Handling Responses

### Working with Response Data

The HTTP response is available in the task's output. You can access:

- The response body (parsed if JSON)
- Status code
- Headers
- Other metadata

```yaml
  - ProcessApiResponse:
      set:
        responseData: ${ . }
        statusCode: ${ .statusCode }
        responseBody: ${ .body }
        responseHeaders: ${ .headers }
        contentType: ${ .headers["content-type"] }
        result: ${ .body.data.result }
      then: NextTask
```

### Status Code Handling

You can conditionally process responses based on status code:

```yaml
  - CheckApiResponse:
      switch:
        - case1:
            then: HandleSuccess
            when: ${ .statusCode >= 200 and .statusCode < 300 }
        - case2:
            then: HandleNotFound
            when: ${ .statusCode == 404 }
        - case3:
            then: HandleAuthError
            when: ${ .statusCode == 401 // .statusCode == 403 }
        - default:
            then: HandleOtherError
```

## Error Handling

### Using Try/Catch

Wrap HTTP calls in a `try` task to handle errors:

```yaml
  - AttemptApiCall:
      try:
        - MakeApiCall:
            call: apiService
            with:
              userId: ${ .userId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleHttpError
                    when: ${ $failure.type == "HTTP_ERROR" }
                - case2:
                    then: HandleTimeout
                    when: ${ $failure.type == "TIMEOUT" }
                - case3:
                    then: HandleGenericError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: ProcessSuccessResponse
```

This example includes retry configuration for transient errors.

### Handling Specific HTTP Status Codes

You can catch specific HTTP errors:

```yaml
  - AttemptApiCall:
      try:
        - MakeApiCall:
            call: apiService
            with:
              userId: ${ .userId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleNotFound
                    when: ${ $failure.status == 404 }
                - case2:
                    then: HandleRateLimited
                    when: ${ $failure.status == 429 }
                - case3:
                    then: HandleServerError
                    when: ${ $failure.status == 500 }
                - case4:
                    then: HandleOtherError
      then: ProcessSuccessResponse
```

## Advanced HTTP Features

### Dynamic URLs

You can dynamically construct URLs based on workflow data:

```yaml
  - PrepareApiCall:
      set:
        apiEnvironment: ${ if .environment == "production" then "api" else "api-dev" end }
        apiVersion: ${ if .useNewApi then "v2" else "v1" end }
        apiUrl: ${ "https://" + .apiEnvironment + ".example.com/" + .apiVersion + "/users" }
      then: MakeApiCall
  - MakeApiCall:
      call: dynamicApi
      with:
        url: ${ .apiUrl }
        method: "GET"
      then: ProcessResponse
```

### Timeouts

You can set custom timeouts for HTTP calls:

```yaml
  - LongRunningCall:
      call: slowApi
      with: {}
      then: ProcessResponse
      timeout:
        after: "PT30S"
```

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

### Following Redirects

Control redirect behavior:

```yaml
  - ApiCallWithRedirects:
      call: apiService
      with:
        followRedirects: true
        maxRedirects: 5
      then: ProcessResponse
```

### Connection Pooling

Configure connection pooling:

```yaml
  - ApiCallWithPooling:
      call: apiService
      with:
        connection:
          poolSize: 10
          keepAlive: true
          keepAliveDuration: "PT30S"
      then: ProcessResponse
```

## Authentication Methods

### Basic Authentication

```yaml
use:
  functions:
    secureApi:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/secure
          authentication:
            basic:
              username: ${ $secrets.username }
              password: ${ $secrets.password }
        query: ${ . }
```

The `${username}` syntax references secrets that should be provided at runtime.

### Bearer Token

```yaml
use:
  functions:
    secureApi:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/secure
          authentication:
            bearer:
              token: ${ $secrets.api_token }
        query: ${ . }
```

### OAuth2

```yaml
use:
  functions:
    secureApi:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/secure
          authentication:
            oauth2:
              authority: "https://auth.example.com/token"
              grant: client_credentials
              client:
                id: ${ $secrets.client_id }
                secret: ${ $secrets.client_secret }
              scopes:
                - "user.read"
                - "orders.write"
        query: ${ . }
```

### API Key

```yaml
use:
  functions:
    secureApi:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/secure
        query: ${ . }
        headers:
          "X-API-Key": ${ $secrets.api_key }
```

The `in` field can be either `header` or `query`.

## Real-World Example: Order Processing API

Here's a complete example that processes an order through multiple API calls:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
do:
  - ValidateOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        shippingAddress: ${ $workflow.input.shippingAddress }
        paymentMethod: ${ $workflow.input.paymentMethod }
      then: ValidateCustomer
  - ValidateCustomer:
      try:
        - CheckCustomer:
            call: http
            with:
              method: get
              endpoint:
                uri: https://api.example.com/customers/{customerId}/validate
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              query:
                checkCredit: "true"
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleInvalidCustomer
                    when: ${ $failure.status == 404 }
                - case2:
                    then: HandleValidationError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: CheckInventory
  - CheckInventory:
      try:
        - VerifyInventory:
            call: http
            with:
              method: post
              endpoint:
                uri: https://api.example.com/inventory/check
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              body:
                items: ${ .items }
      catch:
        as: failure
        then: HandleInventoryError
        retry:
          limit:
            attempt:
              count: 2
      then: EvaluateInventory
  - EvaluateInventory:
      switch:
        - case1:
            then: ProcessPayment
            when: ${ .body.available == true }
        - default:
            then: HandleUnavailableInventory
  - ProcessPayment:
      try:
        - ChargePayment:
            call: http
            with:
              method: post
              endpoint:
                uri: https://api.example.com/payments/process
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              body:
                customerId: ${ .customerId }
                orderId: ${ .orderId }
                amount: ${ .body.totalPrice }
                method: ${ .paymentMethod }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandlePaymentRejected
                    when: ${ $failure.status == 402 }
                - case2:
                    then: HandlePaymentError
        retry:
          limit:
            attempt:
              count: 2
      then: CreateShipment
  - CreateShipment:
      try:
        - ArrangeShipment:
            call: http
            with:
              method: post
              endpoint:
                uri: https://api.example.com/shipments
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              body:
                orderId: ${ .orderId }
                customerId: ${ .customerId }
                items: ${ .items }
                shippingAddress: ${ .shippingAddress }
                trackingInfo:
                  carrier: "preferred"
      catch:
        as: failure
        then: HandleShipmentError
        retry:
          limit:
            attempt:
              count: 3
      then: CompleteOrder
  - CompleteOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
        paymentId: ${ .ChargePayment.body.paymentId }
        trackingNumber: ${ .ArrangeShipment.body.trackingNumber }
        estimatedDelivery: ${ .ArrangeShipment.body.estimatedDelivery }
      then: end
  - HandleInvalidCustomer:
      set:
        status: "REJECTED"
        reason: "Invalid customer account"
      then: end
  - HandleValidationError:
      set:
        status: "ERROR"
        reason: "Customer validation failed"
        error: ${ $context.error }
      then: end
  - HandleInventoryError:
      set:
        status: "ERROR"
        reason: "Inventory check failed"
        error: ${ $context.error }
      then: end
  - HandleUnavailableInventory:
      set:
        status: "REJECTED"
        reason: "Some items are unavailable"
        unavailableItems: ${ .body.unavailableItems }
      then: end
  - HandlePaymentRejected:
      set:
        status: "REJECTED"
        reason: "Payment was rejected"
        details: ${ .body.declineReason }
      then: end
  - HandlePaymentError:
      set:
        status: "ERROR"
        reason: "Payment processing failed"
        error: ${ $context.error }
      then: end
  - HandleShipmentError:
      set:
        status: "ERROR"
        reason: "Shipment creation failed"
        error: ${ $context.error }
      then: end
```

This workflow makes multiple HTTP calls with proper error handling to process an order from validation to shipment.

## Best Practices for HTTP Calls

1. **Use descriptive function names**: Name HTTP functions based on their purpose, not just the endpoint
2. **Include proper error handling**: Always wrap HTTP calls in `try` tasks with appropriate retry policies
3. **Set reasonable timeouts**: Configure timeouts appropriate for the expected response time (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
4. **Validate responses**: Check status codes and response data to ensure they match expectations
5. **Use authentication securely**: Store credentials as secrets, not hardcoded values
6. **Handle rate limiting**: Implement exponential backoff retry strategies for rate-limited APIs
7. **Log meaningful information**: Log request and response details for debugging (excluding sensitive data)
8. **Consider idempotency**: Ensure that retried operations are idempotent
9. **Monitor performance**: Track API call latencies and error rates
10. **Implement circuit breakers**: Prevent cascading failures by failing fast when services are unresponsive

## Troubleshooting Common Issues

### API Returns Unexpected Status Codes

If you're receiving unexpected status codes:
- Check authentication credentials
- Verify request parameters and payload format
- Examine request headers (Content-Type, Accept)
- Check for rate limiting or throttling
- Verify the API endpoint URL

### Handling Binary Responses

For binary responses (files, images):
- Set the appropriate `Accept` header
- Process the response as binary data
- Use base64 encoding if needed

### Dealing with Large Responses

For large API responses:
- Consider pagination if supported by the API
- Process data in chunks
- Use streaming if available

### Connection Issues

If you're experiencing connection issues:
- Check network connectivity
- Verify DNS resolution
- Ensure proper TLS/SSL configuration
- Check for proxy requirements
- Verify firewall rules

## Next Steps

- Learn about [using OpenAPI-defined services](lemline-howto-openapi.md)
- Explore [how to call gRPC functions](lemline-howto-grpc.md)
- Understand [how to access secrets securely](lemline-howto-secrets.md)