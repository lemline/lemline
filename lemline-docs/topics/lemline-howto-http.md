---
title: How to make an HTTP call
---

# How to make an HTTP call

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
functions:
  - name: getWeather
    type: http
    operation: GET
    url: https://api.weather.com/forecast?location={location}

tasks:
  - name: FetchWeather
    type: call
    function: getWeather
    data:
      location: "New York"
    next: ProcessWeatherData
  
  - name: ProcessWeatherData
    type: set
    data:
      temperature: ".temperature"
      conditions: ".conditions"
      message: "The temperature in New York is .temperature°C with .conditions"
    next: NextTask
```

## HTTP Function Definition

The HTTP function definition includes the following elements:

```yaml
functions:
  - name: userService              # Unique function name
    type: http                     # Function type (http)
    operation: GET                 # HTTP method
    url: https://api.example.com/users/{userId}  # URL with path parameters
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
functions:
  - name: getUserProfile
    type: http
    operation: GET
    url: https://api.example.com/users/{userId}

tasks:
  - name: FetchUserProfile
    type: call
    function: getUserProfile
    data:
      userId: ".userId"
    next: ProcessProfile
```

### POST Request with JSON Body

```yaml
functions:
  - name: createOrder
    type: http
    operation: POST
    url: https://api.example.com/orders

tasks:
  - name: SubmitOrder
    type: call
    function: createOrder
    data:
      body:
        customerId: ".customerId"
        items: ".items"
        shippingAddress: ".shippingAddress"
        paymentMethod: ".paymentMethod"
    next: ProcessOrderResponse
```

The `body` field automatically gets serialized as JSON and sent as the request body.

### PUT Request to Update a Resource

```yaml
functions:
  - name: updateUser
    type: http
    operation: PUT
    url: https://api.example.com/users/{userId}

tasks:
  - name: UpdateUserProfile
    type: call
    function: updateUser
    data:
      userId: ".userId"
      body:
        name: ".updatedName"
        email: ".updatedEmail"
        preferences: ".updatedPreferences"
    next: HandleUpdateResponse
```

### DELETE Request

```yaml
functions:
  - name: cancelOrder
    type: http
    operation: DELETE
    url: https://api.example.com/orders/{orderId}

tasks:
  - name: CancelExistingOrder
    type: call
    function: cancelOrder
    data:
      orderId: ".orderId"
      query:
        reason: ".cancellationReason"
    next: HandleCancellationResponse
```

## Request Configuration Options

### Query Parameters

You can add query parameters using the `query` field:

```yaml
- name: SearchProducts
  type: call
  function: productSearch
  data:
    query:
      category: ".productCategory"
      minPrice: ".priceRange.min"
      maxPrice: ".priceRange.max"
      sortBy: "price"
      order: "asc"
  next: ProcessSearchResults
```

These will be appended to the URL as `?category=...&minPrice=...&maxPrice=...&sortBy=price&order=asc`.

### Headers

You can set custom headers using the `headers` field:

```yaml
- name: MakeApiCall
  type: call
  function: apiService
  data:
    headers:
      Content-Type: "application/json"
      Accept: "application/json"
      X-API-Key: ".apiKey"
      X-Request-ID: "$WORKFLOW.id"
  next: ProcessResponse
```

### Request Body Formats

By default, JSON bodies are sent with `Content-Type: application/json`. You can specify other formats:

#### Form Data

```yaml
- name: SubmitForm
  type: call
  function: formProcessor
  data:
    headers:
      Content-Type: "application/x-www-form-urlencoded"
    body:
      name: ".user.name"
      email: ".user.email"
      subscribe: "true"
  next: ProcessFormResponse
```

#### Multipart Form Data

```yaml
- name: UploadFile
  type: call
  function: fileUpload
  data:
    headers:
      Content-Type: "multipart/form-data"
    body:
      file: ".fileContent"
      filename: ".fileName"
      description: ".fileDescription"
  next: ProcessUploadResponse
```

#### Raw Text

```yaml
- name: SendPlainText
  type: call
  function: textProcessor
  data:
    headers:
      Content-Type: "text/plain"
    body: ".textContent"
  next: ProcessTextResponse
```

## Handling Responses

### Working with Response Data

The HTTP response is available in the task's output. You can access:

- The response body (parsed if JSON)
- Status code
- Headers
- Other metadata

```yaml
- name: ProcessApiResponse
  type: set
  data:
    responseData: "."  # The full response
    statusCode: ".statusCode"
    responseBody: ".body"  # Already parsed if JSON
    responseHeaders: ".headers"
    contentType: ".headers['content-type']"
    result: ".body.data.result"
  next: NextTask
```

### Status Code Handling

You can conditionally process responses based on status code:

```yaml
- name: CheckApiResponse
  type: switch
  conditions:
    - condition: ".statusCode >= 200 && .statusCode < 300"
      next: HandleSuccess
    - condition: ".statusCode == 404"
      next: HandleNotFound
    - condition: ".statusCode == 401 || .statusCode == 403"
      next: HandleAuthError
    - condition: true
      next: HandleOtherError
```

## Error Handling

### Using Try/Catch

Wrap HTTP calls in a `try` task to handle errors:

```yaml
- name: AttemptApiCall
  type: try
  retry:
    maxAttempts: 3
    interval: PT2S
    multiplier: 2
    jitter: 0.5
  catch:
    - error: "HTTP_ERROR"
      next: HandleHttpError
    - error: "TIMEOUT"
      next: HandleTimeout
    - error: "*"
      next: HandleGenericError
  do:
    - name: MakeApiCall
      type: call
      function: apiService
      data:
        userId: ".userId"
  next: ProcessSuccessResponse
```

This example includes retry configuration for transient errors.

### Handling Specific HTTP Status Codes

You can catch specific HTTP errors:

```yaml
- name: AttemptApiCall
  type: try
  catch:
    - error: "HTTP_ERROR"
      status: 404
      next: HandleNotFound
    - error: "HTTP_ERROR"
      status: 429
      next: HandleRateLimited
    - error: "HTTP_ERROR"
      status: 500
      next: HandleServerError
    - error: "*"
      next: HandleOtherError
  do:
    - name: MakeApiCall
      type: call
      function: apiService
      data:
        userId: ".userId"
  next: ProcessSuccessResponse
```

## Advanced HTTP Features

### Dynamic URLs

You can dynamically construct URLs based on workflow data:

```yaml
- name: PrepareApiCall
  type: set
  data:
    apiEnvironment: ".environment == 'production' ? 'api' : 'api-dev'"
    apiVersion: ".useNewApi ? 'v2' : 'v1'"
    apiUrl: "https://" + .apiEnvironment + ".example.com/" + .apiVersion + "/users"
  next: MakeApiCall

- name: MakeApiCall
  type: call
  function: dynamicApi
  data:
    url: ".apiUrl"
    method: "GET"
  next: ProcessResponse
```

### Timeouts

You can set custom timeouts for HTTP calls:

```yaml
- name: LongRunningCall
  type: call
  function: slowApi
  data:
    timeout: "PT30S"  # 30 second timeout
  next: ProcessResponse
```

### Following Redirects

Control redirect behavior:

```yaml
- name: ApiCallWithRedirects
  type: call
  function: apiService
  data:
    followRedirects: true
    maxRedirects: 5
  next: ProcessResponse
```

### Connection Pooling

Configure connection pooling:

```yaml
- name: ApiCallWithPooling
  type: call
  function: apiService
  data:
    connection:
      poolSize: 10
      keepAlive: true
      keepAliveDuration: "PT30S"
  next: ProcessResponse
```

## Authentication Methods

### Basic Authentication

```yaml
functions:
  - name: secureApi
    type: http
    operation: GET
    url: https://api.example.com/secure
    auth:
      type: basic
      username: "${username}"
      password: "${password}"
```

The `${username}` syntax references secrets that should be provided at runtime.

### Bearer Token

```yaml
functions:
  - name: secureApi
    type: http
    operation: GET
    url: https://api.example.com/secure
    auth:
      type: bearer
      token: "${api_token}"
```

### OAuth2

```yaml
functions:
  - name: secureApi
    type: http
    operation: GET
    url: https://api.example.com/secure
    auth:
      type: oauth2
      tokenUrl: "https://auth.example.com/token"
      clientId: "${client_id}"
      clientSecret: "${client_secret}"
      scopes:
        - "user.read"
        - "orders.write"
```

### API Key

```yaml
functions:
  - name: secureApi
    type: http
    operation: GET
    url: https://api.example.com/secure
    auth:
      type: apiKey
      name: "X-API-Key"
      in: "header"
      value: "${api_key}"
```

The `in` field can be either `header` or `query`.

## Real-World Example: Order Processing API

Here's a complete example that processes an order through multiple API calls:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
functions:
  - name: validateCustomer
    type: http
    operation: GET
    url: https://api.example.com/customers/{customerId}/validate
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: checkInventory
    type: http
    operation: POST
    url: https://api.example.com/inventory/check
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: processPayment
    type: http
    operation: POST
    url: https://api.example.com/payments/process
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: createShipment
    type: http
    operation: POST
    url: https://api.example.com/shipments
    auth:
      type: bearer
      token: "${api_token}"
tasks:
  - name: ValidateOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      shippingAddress: "$WORKFLOW.input.shippingAddress"
      paymentMethod: "$WORKFLOW.input.paymentMethod"
    next: ValidateCustomer
  
  - name: ValidateCustomer
    type: try
    retry:
      maxAttempts: 3
      interval: PT2S
    catch:
      - error: "HTTP_ERROR"
        status: 404
        next: HandleInvalidCustomer
      - error: "*"
        next: HandleValidationError
    do:
      - name: CheckCustomer
        type: call
        function: validateCustomer
        data:
          customerId: ".customerId"
          query:
            checkCredit: "true"
    next: CheckInventory
  
  - name: CheckInventory
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "*"
        next: HandleInventoryError
    do:
      - name: VerifyInventory
        type: call
        function: checkInventory
        data:
          body:
            items: ".items"
    next: EvaluateInventory
  
  - name: EvaluateInventory
    type: switch
    conditions:
      - condition: ".body.available == true"
        next: ProcessPayment
      - condition: true
        next: HandleUnavailableInventory
  
  - name: ProcessPayment
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "HTTP_ERROR"
        status: 402
        next: HandlePaymentRejected
      - error: "*"
        next: HandlePaymentError
    do:
      - name: ChargePayment
        type: call
        function: processPayment
        data:
          body:
            customerId: ".customerId"
            orderId: ".orderId"
            amount: ".body.totalPrice"
            method: ".paymentMethod"
    next: CreateShipment
  
  - name: CreateShipment
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: HandleShipmentError
    do:
      - name: ArrangeShipment
        type: call
        function: createShipment
        data:
          body:
            orderId: ".orderId"
            customerId: ".customerId"
            items: ".items"
            shippingAddress: ".shippingAddress"
            trackingInfo:
              carrier: "preferred"
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      status: "COMPLETED"
      message: "Order processed successfully"
      paymentId: ".ChargePayment.body.paymentId"
      trackingNumber: ".ArrangeShipment.body.trackingNumber"
      estimatedDelivery: ".ArrangeShipment.body.estimatedDelivery"
    end: true
  
  - name: HandleInvalidCustomer
    type: set
    data:
      status: "REJECTED"
      reason: "Invalid customer account"
    end: true
  
  - name: HandleValidationError
    type: set
    data:
      status: "ERROR"
      reason: "Customer validation failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleInventoryError
    type: set
    data:
      status: "ERROR"
      reason: "Inventory check failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleUnavailableInventory
    type: set
    data:
      status: "REJECTED"
      reason: "Some items are unavailable"
      unavailableItems: ".body.unavailableItems"
    end: true
  
  - name: HandlePaymentRejected
    type: set
    data:
      status: "REJECTED"
      reason: "Payment was rejected"
      details: ".body.declineReason"
    end: true
  
  - name: HandlePaymentError
    type: set
    data:
      status: "ERROR"
      reason: "Payment processing failed"
      error: "$WORKFLOW.error"
    end: true
  
  - name: HandleShipmentError
    type: set
    data:
      status: "ERROR"
      reason: "Shipment creation failed"
      error: "$WORKFLOW.error"
    end: true
```

This workflow makes multiple HTTP calls with proper error handling to process an order from validation to shipment.

## Best Practices for HTTP Calls

1. **Use descriptive function names**: Name HTTP functions based on their purpose, not just the endpoint
2. **Include proper error handling**: Always wrap HTTP calls in `try` tasks with appropriate retry policies
3. **Set reasonable timeouts**: Configure timeouts appropriate for the expected response time
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