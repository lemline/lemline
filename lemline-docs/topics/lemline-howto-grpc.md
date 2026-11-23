---
title: How to call gRPC functions
---

# How to call gRPC functions

> **Not supported by the Lemline runtime yet.** Workflows that use `call: grpc` are accepted, but the task fails when it runs. If the service also exposes an HTTP or JSON interface, call it with `call: http`.


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
use:
  functions:
    userService:
      call: grpc
      with:
        proto:
          endpoint: file:///path/to/users.proto
        service:
          name: users.UserService
          host: grpc.example.com
          port: 443
        method: GetUser
        arguments: {}
do:
  - GetUserDetails:
      call: userService
      with:
        userId: "12345"
      then: ProcessUserData
  - ProcessUserData:
      set:
        username: ${ .user.username }
        email: ${ .user.email }
        message: ${ "Retrieved user \(.username) with email \(.email)" }
      then: NextTask
```

## gRPC Function Definition

The gRPC function definition includes the following elements:

```yaml
use:
  functions:
    productService:
      call: grpc
      with:
        proto:
          endpoint: https://example.com/protos/products.proto
        service:
          name: products.Products
          host: grpc.example.com
          port: 443
        method: GetProduct
        arguments: {}
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
  - GetProductInfo:
      call: productService
      with:
        product_id: ${ .productId }
      then: ProcessProductInfo
```

The input data should match the request message structure defined in the Protocol Buffer.

### Nested Message Structures

For more complex request structures:

```yaml
  - CreateOrder:
      call: orderService
      with:
        customer_id: ${ .customerId }
        items:
          - product_id: ${ .items[0].id }
            quantity: ${ .items[0].quantity }
          - product_id: ${ .items[1].id }
            quantity: ${ .items[1].quantity }
        shipping_address:
          street: ${ .address.street }
          city: ${ .address.city }
          postal_code: ${ .address.zip }
          country: ${ .address.country }
      then: ProcessOrderResponse
```

### Handling Responses

gRPC responses are automatically deserialized and made available as structured data:

```yaml
  - ProcessProductInfo:
      set:
        productName: ${ .name }
        productPrice: ${ .price }
        isAvailable: ${ .in_stock }
        formattedPrice: ${ "$" + (.price | tostring) }
      then: NextTask
```

## Server-Streaming Calls

For gRPC methods that return a stream of responses:

```yaml
use:
  functions:
    stockService:
      call: grpc
      with:
        proto:
          endpoint: file:///path/to/finance.proto
        service:
          name: finance.StockService
          host: finance-api.example.com
          port: 443
        method: WatchStockPrice
        arguments: {}
do:
  - MonitorStock:
      call: stockService
      with:
        symbol: ${ .stockSymbol }
        interval_seconds: 5
      then: ProcessStockData
  - ProcessStockData:
      set:
        pricePoints: ${ . }
        count: ${ . | length }
        latestPrice: ${ .[.length-1].price }
        highestPrice: ${ . | max_by(.price).price }
        lowestPrice: ${ . | min_by(.price).price }
        averagePrice: ${ . | map(.price) | add / .length }
      then: GenerateReport
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
use:
  functions:
    secureService:
      call: grpc
      with:
        proto:
          endpoint: file:///path/to/secure.proto
        service:
          name: secure.SecureService
          host: secure-api.example.com
          port: 443
        method: GetSecureData
        arguments: {}
```

### Authentication

Add authentication metadata:

```yaml
use:
  functions:
    authenticatedService:
      call: grpc
      with:
        proto:
          endpoint: file:///path/to/auth.proto
        service:
          name: auth.AuthService
          host: auth-api.example.com
          port: 443
          authentication:
            bearer:
              token: ${ $secrets.api_token }
        method: GetProtectedResource
        arguments: {}
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
  - AttemptGrpcCall:
      try:
        - GetUserData:
            call: userService
            with:
              userId: ${ .userId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleUnavailable
                    when: ${ $failure.type == "UNAVAILABLE" }
                - case2:
                    then: HandleTimeout
                    when: ${ $failure.type == "DEADLINE_EXCEEDED" }
                - case3:
                    then: HandleNotFound
                    when: ${ $failure.type == "NOT_FOUND" }
                - case4:
                    then: HandleGenericError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: ProcessUserData
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
  - TimeboxedCall:
      call: userService
      with:
        userId: ${ .userId }
      then: ProcessUserData
      timeout:
        after: PT5S
```

### Deadlines

Set absolute deadlines:

```yaml
  - DeadlinedCall:
      call: userService
      with:
        userId: ${ .userId }
      then: ProcessUserData
```

### Custom Metadata

Add custom metadata to the call:

```yaml
  - CallWithMetadata:
      call: userService
      with:
        userId: ${ .userId }
      then: ProcessUserData
```

## Real-World Example: Order Processing with gRPC

Here's a complete example that processes an order using gRPC services:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing with gRPC
do:
  - ReceiveOrder:
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
            call: grpc
            with:
              proto:
                endpoint: file:///protos/customer.proto
              service:
                name: ecommerce.CustomerService
                host: customer-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: ValidateCustomer
              arguments:
                customer_id: ${ .customerId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleInvalidCustomer
                    when: ${ $failure.type == "GRPC_NOT_FOUND" }
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
            call: grpc
            with:
              proto:
                endpoint: file:///protos/inventory.proto
              service:
                name: ecommerce.InventoryService
                host: inventory-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: CheckAvailability
              arguments:
                items:
                  - product_id: ${ .items[0].productId }
                    quantity: ${ .items[0].quantity }
                  - product_id: ${ .items[1].productId }
                    quantity: ${ .items[1].quantity }
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
            then: CalculateTotal
            when: ${ .available == true }
        - default:
            then: HandleUnavailableInventory
  - CalculateTotal:
      try:
        - ComputePrice:
            call: grpc
            with:
              proto:
                endpoint: file:///protos/pricing.proto
              service:
                name: ecommerce.PricingService
                host: pricing-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: CalculateTotal
              arguments:
                customer_id: ${ .customerId }
                items:
                  - product_id: ${ .items[0].productId }
                    quantity: ${ .items[0].quantity }
                  - product_id: ${ .items[1].productId }
                    quantity: ${ .items[1].quantity }
                coupon_code: ${ .couponCode }
      catch:
        as: failure
        then: HandlePricingError
      then: ProcessPayment
  - ProcessPayment:
      try:
        - ChargePayment:
            call: grpc
            with:
              proto:
                endpoint: file:///protos/payment.proto
              service:
                name: ecommerce.PaymentService
                host: payment-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: ProcessPayment
              arguments:
                customer_id: ${ .customerId }
                order_id: ${ .orderId }
                amount: ${ .total.amount }
                currency: ${ .total.currency }
                payment_method:
                  type: ${ .paymentMethod.type }
                  card_token: ${ .paymentMethod.cardToken }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandlePaymentRejected
                    when: ${ $failure.type == "GRPC_FAILED_PRECONDITION" }
                - case2:
                    then: HandlePaymentError
        retry:
          limit:
            attempt:
              count: 2
      then: CreateOrder
  - CreateOrder:
      try:
        - SubmitOrder:
            call: grpc
            with:
              proto:
                endpoint: file:///protos/order.proto
              service:
                name: ecommerce.OrderService
                host: order-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: CreateOrder
              arguments:
                order_id: ${ .orderId }
                customer_id: ${ .customerId }
                items:
                  - product_id: ${ .items[0].productId }
                    quantity: ${ .items[0].quantity }
                    price: ${ .ComputePrice.item_prices[0].price }
                  - product_id: ${ .items[1].productId }
                    quantity: ${ .items[1].quantity }
                    price: ${ .ComputePrice.item_prices[1].price }
                total: ${ .total }
                payment:
                  transaction_id: ${ .ChargePayment.transaction_id }
                  status: ${ .ChargePayment.status }
      catch:
        as: failure
        then: HandleOrderCreationError
        retry:
          limit:
            attempt:
              count: 3
      then: ScheduleShipment
  - ScheduleShipment:
      try:
        - ArrangeShipment:
            call: grpc
            with:
              proto:
                endpoint: file:///protos/shipping.proto
              service:
                name: ecommerce.ShippingService
                host: shipping-api.example.com
                port: 443
                authentication:
                  bearer:
                    token: ${ $secrets.api_token }
              method: ScheduleShipment
              arguments:
                order_id: ${ .orderId }
                items:
                  - product_id: ${ .items[0].productId }
                    quantity: ${ .items[0].quantity }
                  - product_id: ${ .items[1].productId }
                    quantity: ${ .items[1].quantity }
                shipping_address:
                  name: ${ .shippingAddress.name }
                  street: ${ .shippingAddress.street }
                  city: ${ .shippingAddress.city }
                  state: ${ .shippingAddress.state }
                  postal_code: ${ .shippingAddress.postalCode }
                  country: ${ .shippingAddress.country }
                shipping_method: "STANDARD"
      catch:
        as: failure
        then: HandleShippingError
        retry:
          limit:
            attempt:
              count: 3
      then: CompleteOrder
  - CompleteOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
        order_id: ${ .orderId }
        tracking_number: ${ .ArrangeShipment.tracking_number }
        estimated_delivery: ${ .ArrangeShipment.estimated_delivery }
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
```

This workflow uses gRPC services to process an order from validation to shipment, with appropriate error handling at each step.

## Best Practices for gRPC Integration

1. **Define Clear Proto Files**: Use well-structured Protocol Buffer definitions
2. **Version Your APIs**: Include versioning in your service and proto definitions
3. **Implement Proper Error Handling**: Use try/catch blocks with specific error types
4. **Set Reasonable Timeouts**: Configure timeouts appropriate for the expected response time (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
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