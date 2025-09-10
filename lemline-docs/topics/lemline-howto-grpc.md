---
title: How to call gRPC functions
---

# How to call gRPC functions

This guide explains how to integrate gRPC service calls into your Lemline workflows. You'll learn how to define gRPC functions, make unary and streaming calls, handle responses, and implement error handling.

## Understanding gRPC in Lemline

gRPC is a high-performance, open-source universal RPC framework that enables efficient communication between services. Lemline provides native support for gRPC, allowing you to:

- Call gRPC services defined by Protocol Buffers
- Make unary (request/response) calls
- Handle streaming responses (server-streaming)
- Pass structured data with proper serialization
- Utilize client-side secure communication

## Benefits of Using gRPC Integration

Using gRPC in Lemline offers several advantages:

1. **Performance**: gRPC is highly efficient, using HTTP/2 and Protocol Buffers
2. **Type Safety**: Strong typing through Protocol Buffers
3. **Cross-Platform**: Works with services written in any language that supports gRPC
4. **Advanced Features**: Support for streaming, flow control, and multiplexing
5. **Modern Protocol**: Built on HTTP/2 with support for bidirectional streaming
6. **Broad Industry Adoption**: Used in many microservice architectures

## Basic gRPC Structure

To call a gRPC service in Lemline, you need to:

1. Define a gRPC function in the `functions` section
2. Create a `call` task that uses the function
3. Handle the response in subsequent tasks

Here's a basic example:

```yaml
functions:
  - name: userService
    type: grpc
    service: users.UserService
    proto: file:///path/to/users.proto
    operation: GetUser
    host: grpc.example.com:443

tasks:
  - name: GetUserDetails
    type: call
    function: userService
    data:
      userId: "12345"
    next: ProcessUserData
  
  - name: ProcessUserData
    type: set
    data:
      username: ".user.username"
      email: ".user.email"
      message: "Retrieved user .username with email .email"
    next: NextTask
```

## gRPC Function Definition

The gRPC function definition includes the following elements:

```yaml
functions:
  - name: productService         # Unique function name
    type: grpc                   # Function type (grpc)
    service: products.Products   # Fully qualified service name (package.Service)
    proto: https://example.com/protos/products.proto  # Proto file location
    operation: GetProduct        # Method name to call
    host: grpc.example.com:443   # gRPC server hostname and port
```

### Proto File Specification

You can reference the Protocol Buffer definition in several ways:

#### Local File Path

```yaml
proto: file:///path/to/products.proto
```

#### HTTP URL

```yaml
proto: https://example.com/protos/products.proto
```

#### Inline Definition

For simple cases, you can define the proto inline:

```yaml
proto: |
  syntax = "proto3";
  
  package products;
  
  service Products {
    rpc GetProduct(GetProductRequest) returns (GetProductResponse);
  }
  
  message GetProductRequest {
    string product_id = 1;
  }
  
  message GetProductResponse {
    string product_id = 1;
    string name = 2;
    double price = 3;
    bool in_stock = 4;
  }
```

## Making gRPC Calls

### Unary Calls

For simple request/response gRPC calls:

```yaml
- name: GetProductInfo
  type: call
  function: productService
  data:
    product_id: ".productId"  # Matches the field in GetProductRequest
  next: ProcessProductInfo
```

The input data should match the request message structure defined in the Protocol Buffer.

### Nested Message Structures

For more complex request structures:

```yaml
- name: CreateOrder
  type: call
  function: orderService
  data:
    customer_id: ".customerId"
    items:                   # Nested repeated field
      - product_id: ".items[0].id"
        quantity: ".items[0].quantity"
      - product_id: ".items[1].id"
        quantity: ".items[1].quantity"
    shipping_address:        # Nested message
      street: ".address.street"
      city: ".address.city"
      postal_code: ".address.zip"
      country: ".address.country"
  next: ProcessOrderResponse
```

### Handling Responses

gRPC responses are automatically deserialized and made available as structured data:

```yaml
- name: ProcessProductInfo
  type: set
  data:
    productName: ".name"             # Access response fields directly
    productPrice: ".price"
    isAvailable: ".in_stock"
    formattedPrice: "$" + (.price | tostring)
  next: NextTask
```

## Server-Streaming Calls

For gRPC methods that return a stream of responses:

```yaml
functions:
  - name: stockService
    type: grpc
    service: finance.StockService
    proto: file:///path/to/finance.proto
    operation: WatchStockPrice
    host: finance-api.example.com:443

tasks:
  - name: MonitorStock
    type: call
    function: stockService
    data:
      symbol: ".stockSymbol"
      interval_seconds: 5
    streaming:
      mode: "server"
      collect: "all"
      timeout: PT2M
    next: ProcessStockData
  
  - name: ProcessStockData
    type: set
    data:
      pricePoints: "."  # Array of all streamed responses
      count: ". | length"
      latestPrice: ".[.length-1].price"
      highestPrice: ". | max_by(.price).price"
      lowestPrice: ". | min_by(.price).price"
      averagePrice: ". | map(.price) | add / .length"
    next: GenerateReport
```

### Streaming Options

The `streaming` property supports these options:

- `mode`: Type of streaming (currently only "server" is supported)
- `collect`: How to collect results:
  - `all`: Return all responses as an array
  - `last`: Return only the last response
  - `first`: Return only the first response
- `timeout`: How long to listen for streaming responses (ISO 8601 duration)

## Authentication and Security

### TLS/SSL

Enable secure communication with TLS:

```yaml
functions:
  - name: secureService
    type: grpc
    service: secure.SecureService
    proto: file:///path/to/secure.proto
    operation: GetSecureData
    host: secure-api.example.com:443
    tls:
      enabled: true
      verify: true
      ca_cert: "${ca_certificate}"  # Reference to a CA certificate secret
```

### Authentication

Add authentication metadata:

```yaml
functions:
  - name: authenticatedService
    type: grpc
    service: auth.AuthService
    proto: file:///path/to/auth.proto
    operation: GetProtectedResource
    host: auth-api.example.com:443
    auth:
      type: bearer
      token: "${api_token}"  # Reference to a secret
```

Authentication options include:

#### Bearer Token

```yaml
auth:
  type: bearer
  token: "${api_token}"
```

#### Basic Authentication

```yaml
auth:
  type: basic
  username: "${username}"
  password: "${password}"
```

#### Custom Metadata

```yaml
auth:
  type: metadata
  headers:
    x-api-key: "${api_key}"
    x-tenant-id: "${tenant_id}"
```

## Error Handling

### Using Try/Catch

Wrap gRPC calls in a `try` task to handle errors:

```yaml
- name: AttemptGrpcCall
  type: try
  retry:
    maxAttempts: 3
    interval: PT2S
    multiplier: 2
  catch:
    - error: "UNAVAILABLE"
      next: HandleUnavailable
    - error: "DEADLINE_EXCEEDED"
      next: HandleTimeout
    - error: "NOT_FOUND"
      next: HandleNotFound
    - error: "*"
      next: HandleGenericError
  do:
    - name: GetUserData
      type: call
      function: userService
      data:
        userId: ".userId"
  next: ProcessUserData
```

### gRPC-Specific Error Codes

Lemline maps gRPC status codes to specific error types:

| gRPC Status Code | Lemline Error Type |
|------------------|-------------------|
| CANCELLED | GRPC_CANCELLED |
| UNKNOWN | GRPC_UNKNOWN |
| INVALID_ARGUMENT | GRPC_INVALID_ARGUMENT |
| DEADLINE_EXCEEDED | GRPC_DEADLINE_EXCEEDED |
| NOT_FOUND | GRPC_NOT_FOUND |
| ALREADY_EXISTS | GRPC_ALREADY_EXISTS |
| PERMISSION_DENIED | GRPC_PERMISSION_DENIED |
| RESOURCE_EXHAUSTED | GRPC_RESOURCE_EXHAUSTED |
| FAILED_PRECONDITION | GRPC_FAILED_PRECONDITION |
| ABORTED | GRPC_ABORTED |
| OUT_OF_RANGE | GRPC_OUT_OF_RANGE |
| UNIMPLEMENTED | GRPC_UNIMPLEMENTED |
| INTERNAL | GRPC_INTERNAL |
| UNAVAILABLE | GRPC_UNAVAILABLE |
| DATA_LOSS | GRPC_DATA_LOSS |
| UNAUTHENTICATED | GRPC_UNAUTHENTICATED |

You can catch specific gRPC errors:

```yaml
catch:
  - error: "GRPC_NOT_FOUND"
    next: HandleNotFound
  - error: "GRPC_PERMISSION_DENIED"
    next: HandlePermissionIssue
```

## Advanced Features

### Timeouts

Set timeouts for gRPC calls:

```yaml
- name: TimeboxedCall
  type: call
  function: userService
  data:
    userId: ".userId"
  timeout: PT5S  # 5-second timeout
  timeoutNext: HandleTimeout
  next: ProcessUserData
```

### Deadlines

Set absolute deadlines:

```yaml
- name: DeadlinedCall
  type: call
  function: userService
  data:
    userId: ".userId"
  deadline: "2023-12-31T23:59:59Z"  # Call must complete by this time
  timeoutNext: HandleDeadlineExceeded
  next: ProcessUserData
```

### Custom Metadata

Add custom metadata to the call:

```yaml
- name: CallWithMetadata
  type: call
  function: userService
  data:
    userId: ".userId"
  metadata:
    x-client-id: "lemline-workflow"
    x-request-id: "$WORKFLOW.id"
    x-correlation-id: ".correlationId"
  next: ProcessUserData
```

## Real-World Example: Order Processing with gRPC

Here's a complete example that processes an order using gRPC services:

```yaml
id: order-processing
name: Order Processing with gRPC
version: '1.0'
specVersion: '1.0'
start: ReceiveOrder
functions:
  - name: customerService
    type: grpc
    service: ecommerce.CustomerService
    proto: file:///protos/customer.proto
    operation: ValidateCustomer
    host: customer-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: inventoryService
    type: grpc
    service: ecommerce.InventoryService
    proto: file:///protos/inventory.proto
    operation: CheckAvailability
    host: inventory-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: pricingService
    type: grpc
    service: ecommerce.PricingService
    proto: file:///protos/pricing.proto
    operation: CalculateTotal
    host: pricing-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: paymentService
    type: grpc
    service: ecommerce.PaymentService
    proto: file:///protos/payment.proto
    operation: ProcessPayment
    host: payment-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: orderService
    type: grpc
    service: ecommerce.OrderService
    proto: file:///protos/order.proto
    operation: CreateOrder
    host: order-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
  
  - name: shippingService
    type: grpc
    service: ecommerce.ShippingService
    proto: file:///protos/shipping.proto
    operation: ScheduleShipment
    host: shipping-api.example.com:443
    tls:
      enabled: true
    auth:
      type: bearer
      token: "${api_token}"
tasks:
  - name: ReceiveOrder
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
      - error: "GRPC_NOT_FOUND"
        next: HandleInvalidCustomer
      - error: "*"
        next: HandleValidationError
    do:
      - name: CheckCustomer
        type: call
        function: customerService
        data:
          customer_id: ".customerId"
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
        function: inventoryService
        data:
          items:
            - product_id: ".items[0].productId"
              quantity: ".items[0].quantity"
            - product_id: ".items[1].productId"
              quantity: ".items[1].quantity"
    next: EvaluateInventory
  
  - name: EvaluateInventory
    type: switch
    conditions:
      - condition: ".available == true"
        next: CalculateTotal
      - condition: true
        next: HandleUnavailableInventory
  
  - name: CalculateTotal
    type: try
    catch:
      - error: "*"
        next: HandlePricingError
    do:
      - name: ComputePrice
        type: call
        function: pricingService
        data:
          customer_id: ".customerId"
          items:
            - product_id: ".items[0].productId"
              quantity: ".items[0].quantity"
            - product_id: ".items[1].productId"
              quantity: ".items[1].quantity"
          coupon_code: ".couponCode"
    next: ProcessPayment
  
  - name: ProcessPayment
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "GRPC_FAILED_PRECONDITION"
        next: HandlePaymentRejected
      - error: "*"
        next: HandlePaymentError
    do:
      - name: ChargePayment
        type: call
        function: paymentService
        data:
          customer_id: ".customerId"
          order_id: ".orderId"
          amount: ".total.amount"
          currency: ".total.currency"
          payment_method:
            type: ".paymentMethod.type"
            card_token: ".paymentMethod.cardToken"
    next: CreateOrder
  
  - name: CreateOrder
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: HandleOrderCreationError
    do:
      - name: SubmitOrder
        type: call
        function: orderService
        data:
          order_id: ".orderId"
          customer_id: ".customerId"
          items:
            - product_id: ".items[0].productId"
              quantity: ".items[0].quantity"
              price: ".ComputePrice.item_prices[0].price"
            - product_id: ".items[1].productId"
              quantity: ".items[1].quantity"
              price: ".ComputePrice.item_prices[1].price"
          total: ".total"
          payment:
            transaction_id: ".ChargePayment.transaction_id"
            status: ".ChargePayment.status"
    next: ScheduleShipment
  
  - name: ScheduleShipment
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: HandleShippingError
    do:
      - name: ArrangeShipment
        type: call
        function: shippingService
        data:
          order_id: ".orderId"
          items:
            - product_id: ".items[0].productId"
              quantity: ".items[0].quantity"
            - product_id: ".items[1].productId"
              quantity: ".items[1].quantity"
          shipping_address:
            name: ".shippingAddress.name"
            street: ".shippingAddress.street"
            city: ".shippingAddress.city"
            state: ".shippingAddress.state"
            postal_code: ".shippingAddress.postalCode"
            country: ".shippingAddress.country"
          shipping_method: "STANDARD"
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      status: "COMPLETED"
      message: "Order processed successfully"
      order_id: ".orderId"
      tracking_number: ".ArrangeShipment.tracking_number"
      estimated_delivery: ".ArrangeShipment.estimated_delivery"
    end: true
  
  # Error handling tasks
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
  
  # Additional error handlers for other steps...
```

This workflow uses gRPC services to process an order from validation to shipment, with appropriate error handling at each step.

## Best Practices for gRPC Integration

1. **Define Clear Proto Files**: Use well-structured Protocol Buffer definitions
2. **Version Your APIs**: Include versioning in your service and proto definitions
3. **Implement Proper Error Handling**: Use try/catch blocks with specific error types
4. **Set Reasonable Timeouts**: Configure timeouts appropriate for the expected response time
5. **Use TLS for Security**: Always enable TLS for production environments
6. **Manage Connection Lifecycle**: Consider connection pooling for high-volume workflows
7. **Handle Streaming Appropriately**: Set proper timeouts and collection strategies for streaming calls
8. **Monitor Performance**: Track latency and error rates for gRPC services
9. **Implement Circuit Breakers**: Prevent cascading failures with retries and circuit breaking
10. **Keep Proto Definitions Available**: Ensure proto files are accessible to Lemline at runtime

## Troubleshooting Common Issues

### Connection Issues

If you have trouble connecting to gRPC services:
- Verify the host and port are correct
- Check TLS configuration
- Ensure the service is reachable from the Lemline environment
- Verify firewall rules allow gRPC traffic
- Check for any proxy requirements

### Authentication Problems

For authentication issues:
- Verify token/credentials are correct
- Ensure the correct authentication type is being used
- Check token expiration
- Verify the service recognizes the authentication method

### Proto Parsing Errors

If proto parsing fails:
- Verify the proto syntax is correct
- Check for any import statements and ensure referenced protos are available
- Ensure the service name and operation match the proto definition
- Verify the proto version (proto2 vs proto3) is compatible

### Data Type Mismatches

For request/response data issues:
- Ensure your input data matches the proto message structure
- Check for nested message formats
- Verify field names match exactly (snake_case is common in protos)
- Handle numeric types correctly (int32, int64, float, double)

## Next Steps

- Learn about [how to emit and listen for events](lemline-howto-events.md)
- Explore [how to access secrets securely](lemline-howto-secrets.md)
- Understand [how to validate inputs and outputs with schemas](lemline-howto-schemas.md)